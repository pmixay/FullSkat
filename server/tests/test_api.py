"""General API behaviour and the legacy (pre-v2) stateless chat shape: `messages` in, nothing saved."""

from pathlib import Path

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.efforts import ModelEfforts
from fullskat_server.llm import LLMError
from helpers import MODELS, FakeLLM, bearer, parse_sse, token_for

LEGACY = {"messages": [{"role": "user", "content": "hi"}]}


async def test_me_rejects_missing_and_bad_tokens(client: AsyncClient) -> None:
    assert (await client.get("/v1/me")).json() == {
        "error": "missing_token",
        "message": "Authorization: Bearer <token> is required",
    }
    response = await client.get("/v1/me", headers={"Authorization": "Bearer nope"})
    assert response.status_code == 401
    assert response.json()["error"] == "invalid_token"


async def test_unknown_routes_and_bad_query_values_are_json_errors(client: AsyncClient) -> None:
    missing = await client.get("/v1/nothing")
    assert missing.status_code == 404 and missing.json()["error"] == "not_found"
    token = await token_for(client)
    bad = await client.get("/v1/sync?since=abc", headers=bearer(token))
    assert bad.status_code == 400 and bad.json()["error"] == "invalid_request"


async def test_register_and_me_report_models_and_efforts_of_working_providers(tmp_path: Path) -> None:
    app = create_app(settings=Settings(_env_file=None, data_dir=tmp_path, web_search=False))
    app.state.llm = FakeLLM(
        model_list=["openai/gpt-6.1-terra", "openai/gpt-6-luna", "k3.1-pro"],
        efforts={
            "openai/gpt-6.1-terra": ModelEfforts(("low", "high", "max"), "high"),
            "k3.1-pro": ModelEfforts(),
        },
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        registered = (await client.post("/v1/register", json={
            "passcode": "1234", "name": "A", "device_id": "p", "device_kind": "phone"})).json()
        assert registered["model"] == "openai/gpt-6.1-terra"  # the first working provider sets the default
        assert registered["effort"] == "high"
        assert registered["web_search"] is False and registered["figures"] is True
        assert registered["models"] == ["openai/gpt-6.1-terra", "openai/gpt-6-luna", "k3.1-pro"]
        me = (await client.get("/v1/me", headers=bearer(registered["token"]))).json()
        assert me["model_efforts"]["openai/gpt-6.1-terra"] == {"efforts": ["low", "high", "max"], "default": "high"}
        assert me["model_efforts"]["k3.1-pro"] == {"efforts": [], "default": ""}
        assert set(me["model_efforts"]) == set(me["models"])


# ---- legacy chat ------------------------------------------------------------------


async def test_legacy_chat_streams_deltas_and_done_and_saves_nothing(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client, device_kind="watch")
    request = {"dialog_id": "d1", "locale": "ru-RU", "messages": [{"role": "user", "content": "Привет"}]}
    response = await client.post("/v1/chat", json=request, headers={**bearer(token), "Accept": "text/event-stream"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["thinking", "thinking", "delta", "delta", "delta", "done"]
    assert events[0][1] == {"text": "Greeting "}
    assert events[2][1] == {"text": "Hel"}
    assert events[-1][1] == {
        "text": "Hello world",
        "model": "claude-haiku-5-5",
        "usage": {"prompt_tokens": 10, "completion_tokens": 3},
        "thinking": "Greeting the user.",
        "sources": [],
    }
    (messages, model), = app.state.llm.requests
    assert model == "claude-haiku-5-5"
    assert messages[0]["role"] == "system"
    assert "LaTeX" in messages[0]["content"] and "smartwatch" in messages[0]["content"]
    assert "User locale: ru-RU" in messages[0]["content"]
    assert messages[1:] == [{"role": "user", "content": "Привет"}]
    synced = (await client.get("/v1/sync", headers=bearer(token))).json()
    assert synced["dialogs"] == [] and synced["messages"] == []  # stateless


async def test_legacy_chat_passes_effort_and_web_search(client: AsyncClient, app: FastAPI) -> None:
    headers = bearer(await token_for(client))
    messages = [{"role": "user", "content": "hi"}]

    await client.post("/v1/chat", json={"messages": messages}, headers=headers)
    assert app.state.llm.options[-1] == {"effort": None, "web_search": None}

    await client.post("/v1/chat", json={"messages": messages, "effort": None, "web_search": None}, headers=headers)
    assert app.state.llm.options[-1] == {"effort": None, "web_search": None}

    for effort in ("none", "minimal", "low", "medium", "high", "xhigh", "max"):
        ok = await client.post("/v1/chat", json={"messages": messages, "effort": effort, "web_search": False}, headers=headers)
        assert ok.status_code == 200
        assert app.state.llm.options[-1] == {"effort": effort, "web_search": False}

    await client.post("/v1/chat", json={"messages": messages, "web_search": True}, headers=headers)
    assert app.state.llm.options[-1] == {"effort": None, "web_search": True}


async def test_legacy_chat_rejects_invalid_effort_and_web_search(client: AsyncClient, app: FastAPI) -> None:
    headers = bearer(await token_for(client))
    messages = [{"role": "user", "content": "hi"}]
    for extra in ({"effort": "extreme"}, {"effort": ""}, {"effort": 3}, {"web_search": "maybe"}):
        response = await client.post("/v1/chat", json={"messages": messages, **extra}, headers=headers)
        assert response.status_code == 400, extra
        assert response.json()["error"] == "invalid_request"
    assert app.state.llm.options == []


async def test_legacy_chat_done_has_empty_thinking_without_summary(client: AsyncClient, app: FastAPI) -> None:
    app.state.llm = FakeLLM(deltas=["Hi"])
    response = await client.post("/v1/chat", json=LEGACY, headers=bearer(await token_for(client)))
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["delta", "done"]
    assert events[-1][1]["thinking"] == ""


async def test_legacy_chat_model_allowlist_and_errors(client: AsyncClient, app: FastAPI) -> None:
    headers = bearer(await token_for(client))
    messages = [{"role": "user", "content": "hi"}]

    denied = await client.post("/v1/chat", json={"messages": messages, "model": "evil/model"}, headers=headers)
    assert denied.status_code == 400
    assert denied.json()["error"] == "model_not_allowed"

    ok = await client.post("/v1/chat", json={"messages": messages, "model": "claude-sonnet-5-5"}, headers=headers)
    assert ok.status_code == 200
    assert app.state.llm.requests[-1][1] == "claude-sonnet-5-5"

    invalid = await client.post("/v1/chat", json={"messages": []}, headers=headers)
    assert invalid.status_code == 400
    assert invalid.json()["error"] == "invalid_request"

    app.state.llm = FakeLLM(error=LLMError("upstream_auth", "bad key"))
    failed = await client.post("/v1/chat", json={"messages": messages}, headers=headers)
    assert parse_sse(failed.text) == [("error", {"error": "upstream_auth", "message": "bad key"})]


async def test_legacy_chat_error_event_carries_retry_after(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    app.state.llm = FakeLLM(error=LLMError("upstream_quota", "credit balance too low", retry_after=90))
    response = await client.post("/v1/chat", json=LEGACY, headers=bearer(token))
    assert 'event: error\ndata: {"error": "upstream_quota", "message": "credit balance too low", "retry_after": 90}' in response.text


async def test_chat_without_a_working_provider_or_with_a_down_one(tmp_path: Path) -> None:
    app = create_app(settings=Settings(_env_file=None, data_dir=tmp_path, kimi_api_key="k"))
    app.state.llm = FakeLLM(model_list=[])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        headers = bearer(await token_for(client))
        none = await client.post("/v1/chat", json=LEGACY, headers=headers)
        assert none.status_code == 503 and none.json()["error"] == "no_provider"

        app.state.llm = FakeLLM(model_list=["claude-haiku-5-5"])
        down = await client.post("/v1/chat", json={**LEGACY, "model": "k3.1-pro"}, headers=headers)
        assert down.status_code == 503 and down.json()["error"] == "provider_unavailable"
        unknown = await client.post("/v1/chat", json={**LEGACY, "model": "gpt-9"}, headers=headers)
        assert unknown.status_code == 400 and unknown.json()["error"] == "model_not_allowed"


async def test_chat_body_size_limit(client: AsyncClient) -> None:
    huge = {"messages": [{"role": "user", "content": "x" * (64 * 1024)}]}
    response = await client.post("/v1/chat", json=huge, headers=bearer(await token_for(client)))
    assert response.status_code == 413
    assert response.json()["error"] == "payload_too_large"


async def test_legacy_chat_history_is_trimmed(client: AsyncClient, app: FastAPI) -> None:
    history = [{"role": "user" if i % 2 == 0 else "assistant", "content": f"m{i}"} for i in range(40)]
    response = await client.post("/v1/chat", json={"messages": history}, headers=bearer(await token_for(client)))
    assert response.status_code == 200
    sent = app.state.llm.requests[-1][0]
    assert len(sent) == 25  # system prompt + last 24
    assert sent[-1]["content"] == "m39"


async def test_legacy_chat_done_carries_merged_sources(client: AsyncClient, app: FastAPI) -> None:
    from fullskat_server.sources import Source, Sources

    first = Sources((Source("https://a.example/", "A"),))
    second = Sources((Source("https://a.example", "A again"), Source("https://b.example/x", "")))
    app.state.llm = FakeLLM(deltas=["Hi", first, second])
    response = await client.post("/v1/chat", json={"messages": [{"role": "user", "content": "news?"}]},
                                 headers=bearer(await token_for(client)))
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["delta", "done"]  # sources travel only in done
    assert events[-1][1]["text"] == "Hi"
    assert events[-1][1]["sources"] == [
        {"url": "https://a.example/", "title": "A"},
        {"url": "https://b.example/x", "title": ""},
    ]


def test_models_fixture_matches_the_anthropic_allowlist(settings: Settings) -> None:
    assert settings.provider_models("anthropic") == MODELS
