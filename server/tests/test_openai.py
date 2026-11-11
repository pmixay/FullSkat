"""Responses gateway compatibility and the unchanged watch protocol."""

import json
from pathlib import Path

import httpx
import pytest

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.llm import LLMError, OpenAILLM, Thinking, Usage, create_llm


MODELS = ["gpt-6.1-terra", "gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra"]


def settings(tmp_path: Path, **kwargs) -> Settings:
    return Settings(_env_file=None, data_dir=tmp_path, openai_api_key="test-key",
                    openai_base_url="http://gateway/v1", **kwargs)


def sse(*events: dict) -> bytes:
    return "".join(f"event: {event['type']}\ndata: {json.dumps(event)}\n\n" for event in events).encode()


def completed() -> dict:
    return {"type": "response.completed", "response": {"usage": {"input_tokens": 12, "output_tokens": 7}}}


def catalog() -> dict:
    return {"data": [{"id": model, "metadata": {
        "supported_reasoning_levels": [{"effort": effort} for effort in ("low", "medium", "high", "max")],
        "default_reasoning_level": "low",
    }} for model in MODELS]}


async def test_gateway_stream_translates_to_watch_items(tmp_path: Path):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, content=sse(
            {"type": "response.reasoning_summary_text.delta", "delta": "Thinking."},
            {"type": "response.output_text.delta", "delta": "Привет"}, completed(),
        ))

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        llm = OpenAILLM(settings(tmp_path), client)
        items = [item async for item in llm.stream([
            {"role": "system", "content": "Be brief."},
            {"role": "user", "content": "Hello"},
        ], MODELS[0], effort="low", web_search=True)]
    assert items == [Thinking("Thinking."), "Привет", Usage(12, 7)]
    request = requests[0]
    assert str(request.url) == "http://gateway/v1/responses"
    assert request.headers["authorization"] == "Bearer test-key"
    body = json.loads(request.content)
    assert body["input"] == [{"role": "user", "content": "Hello"}]
    assert body["instructions"] == "Be brief."
    assert body["store"] is False and body["stream"] is True
    assert body["reasoning"] == {"effort": "low", "summary": "auto"}
    assert body["tools"] == [{"type": "web_search"}]
    assert "max_tool_calls" not in body  # Codex backend rejects this parameter.


async def test_openai_respects_search_and_summary_switches(tmp_path: Path):
    def handler(request):
        body = json.loads(request.content)
        assert "tools" not in body and "reasoning" not in body
        return httpx.Response(200, content=sse(
            {"type": "response.reasoning_summary_text.delta", "delta": "hidden"}, completed()))

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        llm = OpenAILLM(settings(tmp_path, web_search=False, show_thinking=False), client)
        assert [x async for x in llm.stream([], MODELS[0], web_search=True)] == [Usage(12, 7)]


@pytest.mark.parametrize(("status", "body", "code"), [
    (401, {"error": {"message": "Invalid key"}}, "upstream_auth"),
    (429, {"error": {"message": "Slow down"}}, "upstream_rate_limited"),
    (402, {"error": {"message": "Quota exhausted"}}, "upstream_quota"),
])
async def test_gateway_http_errors_keep_retry_after(tmp_path: Path, status, body, code):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(status, json=body, headers={"Retry-After": "42"})
    )) as client:
        with pytest.raises(LLMError) as caught:
            _ = [x async for x in OpenAILLM(settings(tmp_path), client).stream([], MODELS[0])]
    assert caught.value.code == code and caught.value.retry_after == 42


@pytest.mark.parametrize("payload", [
    sse({"type": "response.output_text.delta", "delta": "Partial"}),
    sse({"type": "response.incomplete", "response": {"incomplete_details": {"reason": "max_output_tokens"}}}),
    b"data: invalid json\n\n",
])
async def test_gateway_truncation_never_succeeds(tmp_path: Path, payload):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, content=payload)
    )) as client:
        with pytest.raises(LLMError, match="stream|response|max_output_tokens"):
            _ = [x async for x in OpenAILLM(settings(tmp_path), client).stream([], MODELS[0])]


async def test_gateway_stream_error_maps_quota(tmp_path: Path):
    payload = sse({"type": "response.failed", "response": {
        "error": {"code": "insufficient_quota", "message": "No credits", "retry_after_seconds": 60}}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, content=payload)
    )) as client:
        with pytest.raises(LLMError) as caught:
            _ = [x async for x in OpenAILLM(settings(tmp_path), client).stream([], MODELS[0])]
    assert caught.value.code == "upstream_quota" and caught.value.retry_after == 60


async def test_gateway_multiline_sse_and_unterminated_final_frame(tmp_path: Path):
    payload = b'data: {"type":\n' + b'data: "response.completed", "response": {}}'
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, content=payload)
    )) as client:
        assert [x async for x in OpenAILLM(settings(tmp_path), client).stream([], MODELS[0])] == [Usage(0, 0)]


async def test_gateway_efforts_usage_and_recovery(tmp_path: Path):
    healthy = True

    def handler(request):
        if not healthy:
            return httpx.Response(401, json={"error": {"message": "Revoked"}})
        if request.url.path.endswith("/models"):
            return httpx.Response(200, json=catalog())
        return httpx.Response(200, json={"limits": [
            {"remaining_value": 75, "max_value": 100},
            {"remaining_value": 40, "max_value": 100},
        ]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        cfg = settings(tmp_path, openai_model_efforts={MODELS[1]: "low,high"})
        router = create_llm(cfg, client)
        assert cfg.provider_models("openai") == MODELS
        assert all(cfg.provider_for(m) == "openai" for m in MODELS)
        assert (await router.models())[1] == MODELS
        info = await router.providers_info()
        assert info["openai"]["usage"]["remaining"] == .4
        efforts = await router.model_efforts(MODELS)
        assert efforts[MODELS[0]].default == "medium"
        assert efforts[MODELS[1]].efforts == ("low", "high")
        assert efforts[MODELS[1]].default == "low"
        healthy = False
        router._health.clear()
        assert await router.models() == ("", [])
        healthy = True
        router._health.clear()
        assert (await router.models())[1] == MODELS


async def test_existing_pairing_works_after_server_restart(tmp_path: Path):
    cfg = settings(tmp_path)

    def handler(request):
        if request.url.path.endswith("/models"):
            return httpx.Response(200, json=catalog())
        if request.url.path.endswith("/usage"):
            return httpx.Response(404)
        return httpx.Response(200, content=sse(
            {"type": "response.output_text.delta", "delta": "OK"}, completed()))

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as gateway:
        app = create_app(cfg)
        app.state.llm = create_llm(cfg, gateway)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://watch") as watch:
                paired = await watch.post("/v1/pair", json={"device_id": "watch", "passcode": "1234"})
                assert paired.status_code == 201
                auth = {"Authorization": f"Bearer {paired.json()['token']}"}
        state = (tmp_path / "state.json").read_bytes()
        restarted = create_app(cfg)
        restarted.state.llm = create_llm(cfg, gateway)
        async with restarted.router.lifespan_context(restarted):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(restarted), base_url="http://watch") as watch:
                info = await watch.get("/v1/me", headers=auth)
                assert info.status_code == 200 and info.json()["models"] == MODELS
                assert info.json()["providers"]["openai"]["ok"] is True
                for model in MODELS:
                    result = await watch.post("/v1/chat", headers=auth, json={
                        "model": model, "messages": [{"role": "user", "content": "Hello"}]})
                    assert result.status_code == 200
                    assert 'event: delta\ndata: {"text": "OK"}' in result.text
                    assert "event: done" in result.text and "event: error" not in result.text
        assert (tmp_path / "state.json").read_bytes() == state
