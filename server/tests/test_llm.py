from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import anthropic
import httpx
import pytest

from fullskat_server.config import Settings
from fullskat_server.llm import AnthropicLLM, LLMError, OpenRouterLLM, Thinking, Usage, create_llm, parse_openrouter_delta


def make_settings(tmp_path: Path, **overrides: Any) -> Settings:
    return Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="test-key", **overrides)


def final_message(stop_reason: str = "end_turn", content: Any = None, inp: int = 10, out: int = 3) -> SimpleNamespace:
    usage = SimpleNamespace(
        input_tokens=inp, output_tokens=out, cache_read_input_tokens=None, cache_creation_input_tokens=None
    )
    return SimpleNamespace(stop_reason=stop_reason, content=content or [], usage=usage)


def content_delta(delta_type: str, **fields: Any) -> SimpleNamespace:
    return SimpleNamespace(type="content_block_delta", index=0, delta=SimpleNamespace(type=delta_type, **fields))


class FakeStream:
    """Mimics the SDK stream: async-iterable raw events plus text_stream and get_final_message."""

    def __init__(self, events: Any, text_stream: Any, get_final_message: Any) -> None:
        self._events = events
        self.text_stream = text_stream
        self.get_final_message = get_final_message

    def __aiter__(self) -> Any:
        return self._events


class FakeMessages:
    """Stands in for AsyncAnthropic.messages; each stream() call plays the next scripted turn."""

    def __init__(self, turns: list[tuple[list[Any], SimpleNamespace] | Exception]) -> None:
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
                if isinstance(delta, str):
                    yield delta

        async def events():
            for delta in deltas:
                if isinstance(delta, str):
                    yield content_delta("text_delta", text=delta)
                else:  # ("thinking", "piece")
                    yield content_delta("thinking_delta", thinking=delta[1])

        async def get_final_message() -> SimpleNamespace:
            return final

        yield FakeStream(events(), text_stream(), get_final_message)


def llm_with(settings: Settings, turns: list[Any]) -> tuple[AnthropicLLM, FakeMessages]:
    llm = AnthropicLLM(settings)
    fake = FakeMessages(turns)
    llm._client = SimpleNamespace(messages=fake)  # type: ignore[assignment]
    return llm, fake


async def collect(
    llm: AnthropicLLM, messages: list[dict[str, str]], model: str = "claude-haiku-5-5", **options: Any
) -> list[Any]:
    return [item async for item in llm.stream(messages, model, **options)]


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
    assert "temperature" not in request


async def test_anthropic_streams_thinking_summary(tmp_path: Path) -> None:
    turn = ([("thinking", "Let me "), ("thinking", "check."), ("thinking", ""), "Hi", "", " there"], final_message())
    llm, fake = llm_with(make_settings(tmp_path), [turn])
    items = await collect(llm, MESSAGES)
    assert items == [
        Thinking("Let me "),
        Thinking("check."),
        "Hi",
        " there",
        Usage(prompt_tokens=10, completion_tokens=3),
    ]
    assert fake.requests[0]["thinking"] == {"type": "adaptive", "display": "summarized"}


async def test_anthropic_thinking_request_can_be_disabled(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path, show_thinking=False), [(["ok"], final_message())])
    await collect(llm, MESSAGES)
    assert "thinking" not in fake.requests[0]


async def test_anthropic_web_search_and_effort_can_be_disabled(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path, web_search=False, anthropic_effort=""), [([], final_message())])
    await collect(llm, MESSAGES)
    assert "tools" not in fake.requests[0]
    assert "output_config" not in fake.requests[0]


async def test_anthropic_effort_override_and_web_search_off(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path), [([], final_message())])
    await collect(llm, MESSAGES, effort="max", web_search=False)
    assert fake.requests[0]["output_config"] == {"effort": "max"}
    assert "tools" not in fake.requests[0]


async def test_anthropic_web_search_true_keeps_default_tool_and_effort(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path), [([], final_message())])
    await collect(llm, MESSAGES, web_search=True)
    assert fake.requests[0]["output_config"] == {"effort": "medium"}
    assert fake.requests[0]["tools"][0]["name"] == "web_search"


async def test_anthropic_client_cannot_enable_web_search_when_server_disabled(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path, web_search=False, anthropic_effort=""), [([], final_message())])
    await collect(llm, MESSAGES, effort="high", web_search=True)
    assert "tools" not in fake.requests[0]
    assert fake.requests[0]["output_config"] == {"effort": "high"}


async def test_anthropic_resumes_pause_turn(tmp_path: Path) -> None:
    paused = final_message("pause_turn", content=["server-tool-blocks"], inp=100, out=5)
    turns = [([("thinking", "t"), "a"], paused), (["b"], final_message(inp=7, out=2))]
    llm, fake = llm_with(make_settings(tmp_path), turns)
    items = await collect(llm, MESSAGES)
    assert items == [Thinking("t"), "a", "b", Usage(prompt_tokens=107, completion_tokens=7)]
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


def test_openrouter_body_effort_override_maps_max_and_honors_web_flag(tmp_path: Path) -> None:
    messages = [{"role": "user", "content": "hi"}]
    llm = OpenRouterLLM(make_settings(tmp_path, llm_provider="openrouter"), httpx.AsyncClient())
    assert llm._body(messages, "m")["reasoning"] == {"effort": "high"}  # server default
    assert llm._body(messages, "m", effort="max")["reasoning"] == {"effort": "xhigh"}
    assert llm._body(messages, "m", effort="low")["reasoning"] == {"effort": "low"}
    assert "plugins" not in llm._body(messages, "m", web_search=False)
    assert "plugins" in llm._body(messages, "m", web_search=True)

    off = OpenRouterLLM(
        make_settings(tmp_path, llm_provider="openrouter", web_search=False, openrouter_reasoning_effort=""),
        httpx.AsyncClient(),
    )
    body = off._body(messages, "m", effort="medium", web_search=True)
    assert "plugins" not in body  # the server switch wins
    assert body["reasoning"] == {"effort": "medium"}
    assert "reasoning" not in off._body(messages, "m")


def test_openrouter_delta_parsing_extracts_reasoning() -> None:
    assert parse_openrouter_delta({"content": "hi"}) == ["hi"]
    assert parse_openrouter_delta({"reasoning": "hmm", "content": ""}) == [Thinking("hmm")]
    assert parse_openrouter_delta({"reasoning_content": "hmm"}) == [Thinking("hmm")]
    assert parse_openrouter_delta({"reasoning": "a", "content": "b"}) == [Thinking("a"), "b"]
    assert parse_openrouter_delta({"reasoning": None, "content": None, "role": "assistant"}) == []
