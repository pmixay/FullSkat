from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import anthropic
import httpx
import pytest

from fullskat_server.config import Settings
from fullskat_server.llm import AnthropicLLM, LLMError, OpenRouterLLM, Usage, create_llm


def make_settings(tmp_path: Path, **overrides: Any) -> Settings:
    return Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="test-key", **overrides)


def final_message(stop_reason: str = "end_turn", content: Any = None, inp: int = 10, out: int = 3) -> SimpleNamespace:
    usage = SimpleNamespace(
        input_tokens=inp, output_tokens=out, cache_read_input_tokens=None, cache_creation_input_tokens=None
    )
    return SimpleNamespace(stop_reason=stop_reason, content=content or [], usage=usage)


class FakeMessages:
    """Stands in for AsyncAnthropic.messages; each stream() call plays the next scripted turn."""

    def __init__(self, turns: list[tuple[list[str], SimpleNamespace] | Exception]) -> None:
        self.turns = list(turns)
        self.requests: list[dict[str, Any]] = []

    @asynccontextmanager
    async def stream(self, **request: Any):
        self.requests.append(request)
        turn = self.turns.pop(0)
        if isinstance(turn, Exception):
            raise turn
        deltas, final = turn

        async def text_stream():
            for delta in deltas:
                yield delta

        async def get_final_message() -> SimpleNamespace:
            return final

        yield SimpleNamespace(text_stream=text_stream(), get_final_message=get_final_message)


def llm_with(settings: Settings, turns: list[Any]) -> tuple[AnthropicLLM, FakeMessages]:
    llm = AnthropicLLM(settings)
    fake = FakeMessages(turns)
    llm._client = SimpleNamespace(messages=fake)  # type: ignore[assignment]
    return llm, fake


async def collect(llm: AnthropicLLM, messages: list[dict[str, str]], model: str = "claude-haiku-5-5") -> list[Any]:
    return [item async for item in llm.stream(messages, model)]


MESSAGES = [
    {"role": "system", "content": "Be brief."},
    {"role": "assistant", "content": "dangling"},
    {"role": "user", "content": "weather?"},
]


def test_default_provider_is_anthropic_with_haiku(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    assert settings.llm_provider == "anthropic"
    assert settings.chat_model == "claude-haiku-5-5"
    assert settings.allowed_models[0] == "claude-haiku-5-5"
    assert isinstance(create_llm(settings, httpx.AsyncClient()), AnthropicLLM)


def test_openrouter_provider_uses_its_own_models(tmp_path: Path) -> None:
    settings = make_settings(tmp_path, llm_provider="openrouter")
    assert settings.chat_model == "openai/gpt-5.6-luna"
    assert "anthropic/claude-sonnet-5" in settings.allowed_models
    assert isinstance(create_llm(settings, httpx.AsyncClient()), OpenRouterLLM)


async def test_anthropic_request_has_system_effort_and_web_search(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path), [(["Hel", "lo"], final_message())])
    items = await collect(llm, MESSAGES)
    assert items == ["Hel", "lo", Usage(prompt_tokens=10, completion_tokens=3)]
    (request,) = fake.requests
    assert request["model"] == "claude-haiku-5-5"
    assert request["system"] == "Be brief."
    assert request["messages"] == [{"role": "user", "content": "weather?"}]  # leading assistant turn dropped
    assert request["output_config"] == {"effort": "medium"}
    assert request["tools"] == [{"type": "web_search_20250305", "name": "web_search", "max_uses": 3}]
    assert "thinking" not in request and "temperature" not in request


async def test_anthropic_web_search_and_effort_can_be_disabled(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path, web_search=False, anthropic_effort=""), [([], final_message())])
    await collect(llm, MESSAGES)
    assert "tools" not in fake.requests[0]
    assert "output_config" not in fake.requests[0]


async def test_anthropic_resumes_pause_turn(tmp_path: Path) -> None:
    paused = final_message("pause_turn", content=["server-tool-blocks"], inp=100, out=5)
    llm, fake = llm_with(make_settings(tmp_path), [(["a"], paused), (["b"], final_message(inp=7, out=2))])
    items = await collect(llm, MESSAGES)
    assert items == ["a", "b", Usage(prompt_tokens=107, completion_tokens=7)]
    assert fake.requests[1]["messages"][-1] == {"role": "assistant", "content": ["server-tool-blocks"]}


async def test_anthropic_refusal_and_missing_key(tmp_path: Path) -> None:
    llm, _ = llm_with(make_settings(tmp_path), [([], final_message("refusal"))])
    with pytest.raises(LLMError) as refused:
        await collect(llm, MESSAGES)
    assert refused.value.code == "upstream_error"

    keyless = AnthropicLLM(Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key=""))
    with pytest.raises(LLMError) as missing:
        await collect(keyless, MESSAGES)
    assert missing.value.code == "upstream_auth"


async def test_anthropic_errors_map_to_protocol_codes(tmp_path: Path) -> None:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")

    def status_error(status: int) -> anthropic.APIStatusError:
        response = httpx.Response(status, request=request, json={"error": {"message": f"http {status}"}})
        return anthropic.APIStatusError(f"http {status}", response=response, body=None)

    for status, code in [(401, "upstream_auth"), (429, "upstream_rate_limited"), (529, "upstream_error")]:
        llm, _ = llm_with(make_settings(tmp_path), [status_error(status)])
        with pytest.raises(LLMError) as caught:
            await collect(llm, MESSAGES)
        assert caught.value.code == code

    llm, _ = llm_with(make_settings(tmp_path), [anthropic.APIConnectionError(request=request)])
    with pytest.raises(LLMError) as caught:
        await collect(llm, MESSAGES)
    assert caught.value.code == "upstream_error"


def test_openrouter_body_enables_web_plugin(tmp_path: Path) -> None:
    llm = OpenRouterLLM(make_settings(tmp_path, llm_provider="openrouter"), httpx.AsyncClient())
    body = llm._body([{"role": "user", "content": "hi"}], "openai/gpt-5.5")
    assert body["plugins"] == [{"id": "web", "max_results": 3}]
    off = OpenRouterLLM(make_settings(tmp_path, llm_provider="openrouter", web_search=False), httpx.AsyncClient())
    assert "plugins" not in off._body([{"role": "user", "content": "hi"}], "openai/gpt-5.5")
