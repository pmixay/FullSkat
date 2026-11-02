from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import json

import anthropic
import httpx
import pytest

from fullskat_server.config import Settings
from fullskat_server.efforts import ModelEfforts, resolve_effort
from fullskat_server.llm import (
    AnthropicLLM,
    UsageLeft,
    Health,
    KimiLLM,
    LLMError,
    MultiLLM,
    OpenRouterLLM,
    Thinking,
    Usage,
    create_llm,
    error_code_for_status,
    parse_openrouter_delta,
    parse_retry_after,
)


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


def test_providers_are_enabled_by_their_api_keys(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)  # only ANTHROPIC_API_KEY set
    assert settings.configured_providers == ["anthropic"]
    llm = create_llm(settings, httpx.AsyncClient())
    assert isinstance(llm, MultiLLM)
    assert isinstance(llm.providers["anthropic"], AnthropicLLM)

    everything = make_settings(tmp_path, openrouter_api_key="o", kimi_api_key="k")
    assert everything.configured_providers == ["anthropic", "openrouter", "kimi"]
    providers = create_llm(everything, httpx.AsyncClient()).providers
    assert isinstance(providers["openrouter"], OpenRouterLLM) and isinstance(providers["kimi"], KimiLLM)

    nothing = Settings(_env_file=None, data_dir=tmp_path)
    assert nothing.configured_providers == [] and create_llm(nothing, httpx.AsyncClient()).providers == {}


def test_model_lists_per_provider(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    assert settings.provider_models("anthropic")[0] == "claude-haiku-5-5"
    assert settings.provider_models("openrouter") == ["openai/gpt-6.1-terra", "openai/gpt-6-luna"]
    assert settings.provider_models("kimi") == ["k3.1-pro", "k3", "kimi-for-coding"]
    assert settings.provider_for("k3.1-pro") == "kimi"
    assert settings.provider_for("openai/gpt-6-luna") == "openrouter"
    assert settings.provider_for("nope") is None


async def test_anthropic_request_has_system_effort_and_web_search(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path), [(["Hel", "lo"], final_message())])
    items = await collect(llm, MESSAGES)
    assert items == ["Hel", "lo", Usage(prompt_tokens=10, completion_tokens=3)]
    (request,) = fake.requests
    assert request["model"] == "claude-haiku-5-5"
    assert request["system"] == "Be brief."
    assert request["messages"] == [{"role": "user", "content": "weather?"}]  # leading assistant turn dropped
    assert "output_config" not in request  # effort is chosen by MultiLLM
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


async def test_anthropic_web_search_can_be_disabled(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path, web_search=False), [([], final_message())])
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
    await collect(llm, MESSAGES, effort="medium", web_search=True)
    assert fake.requests[0]["output_config"] == {"effort": "medium"}
    assert fake.requests[0]["tools"][0]["name"] == "web_search"


async def test_anthropic_client_cannot_enable_web_search_when_server_disabled(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path, web_search=False), [([], final_message())])
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
    llm = OpenRouterLLM(make_settings(tmp_path), httpx.AsyncClient())
    body = llm._body([{"role": "user", "content": "hi"}], "openai/gpt-5.5")
    assert body["plugins"] == [{"id": "web", "max_results": 3}]
    off = OpenRouterLLM(make_settings(tmp_path, web_search=False), httpx.AsyncClient())
    assert "plugins" not in off._body([{"role": "user", "content": "hi"}], "openai/gpt-5.5")


def test_openrouter_body_sends_the_given_effort_and_honors_web_flag(tmp_path: Path) -> None:
    messages = [{"role": "user", "content": "hi"}]
    llm = OpenRouterLLM(make_settings(tmp_path), httpx.AsyncClient())
    assert "reasoning" not in llm._body(messages, "m")
    assert llm._body(messages, "m", effort="max")["reasoning"] == {"effort": "max"}
    assert llm._body(messages, "m", effort="low")["reasoning"] == {"effort": "low"}
    assert "plugins" not in llm._body(messages, "m", web_search=False)
    assert "plugins" in llm._body(messages, "m", web_search=True)

    off = OpenRouterLLM(make_settings(tmp_path, web_search=False), httpx.AsyncClient())
    assert "plugins" not in off._body(messages, "m", web_search=True)  # the server switch wins


def test_openrouter_delta_parsing_extracts_reasoning() -> None:
    assert parse_openrouter_delta({"content": "hi"}) == ["hi"]
    assert parse_openrouter_delta({"reasoning": "hmm", "content": ""}) == [Thinking("hmm")]
    assert parse_openrouter_delta({"reasoning_content": "hmm"}) == [Thinking("hmm")]
    assert parse_openrouter_delta({"reasoning": "a", "content": "b"}) == [Thinking("a"), "b"]
    assert parse_openrouter_delta({"reasoning": None, "content": None, "role": "assistant"}) == []


# ---- efforts, routing, catalog, Kimi ----------------------------------------


def sse(*chunks: Any) -> bytes:
    lines = [f"data: {json.dumps(c)}\n\n" for c in chunks if not isinstance(c, str)]
    return ("".join(lines) + "data: [DONE]\n\n").encode()


def mock_client(handler: Any) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def test_resolve_effort_picks_the_nearest_supported_level() -> None:
    kimi = ("none", "low", "high", "max")
    assert resolve_effort("high", kimi) == "high"
    assert resolve_effort("xhigh", kimi) == "high"  # tie between high and max goes lower
    assert resolve_effort("medium", kimi) == "low"
    assert resolve_effort("minimal", kimi) == "low"  # "none" is never a stand-in for a real level
    assert resolve_effort("none", kimi) == "none"
    assert resolve_effort("high", ()) is None
    assert resolve_effort(None, kimi) is None


async def test_openrouter_efforts_come_from_the_catalog(tmp_path: Path) -> None:
    calls: list[str] = []
    catalog = {
        "data": [
            {"id": "kimi/k3", "reasoning": {"supported_efforts": ["max", "high", "low", "none"], "default_effort": "max"}},
            {"id": "kimi/k2", "reasoning": {"mandatory": False, "default_enabled": True}},
            {"id": "plain/model"},
            {"id": "openai/gpt", "reasoning": {"supported_efforts": ["max", "xhigh", "medium"], "default_effort": "medium"}},
        ]
    }

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(200, json=catalog)

    llm = OpenRouterLLM(make_settings(tmp_path), mock_client(handler))
    assert await llm.efforts("kimi/k3") == ModelEfforts(("none", "low", "high", "max"), "high")  # server default "high"
    assert await llm.efforts("kimi/k2") == ModelEfforts()  # on/off only
    assert await llm.efforts("plain/model") == ModelEfforts()
    assert await llm.efforts("openai/gpt") == ModelEfforts(("medium", "xhigh", "max"), "medium")  # "high" is missing: tie goes lower
    assert len(calls) == 1 and calls[0].endswith("/models")  # cached


async def test_openrouter_efforts_fall_back_when_the_catalog_is_unreachable(tmp_path: Path) -> None:
    llm = OpenRouterLLM(
        make_settings(tmp_path), mock_client(lambda request: httpx.Response(500))
    )
    assert await llm.efforts("any/model") == ModelEfforts(("low", "medium", "high", "xhigh"), "high")


async def test_anthropic_efforts_come_from_model_capabilities(tmp_path: Path) -> None:
    def capability(**levels: bool) -> SimpleNamespace:
        effort = {name: SimpleNamespace(supported=ok) for name, ok in levels.items()}
        return SimpleNamespace(capabilities=SimpleNamespace(effort=SimpleNamespace(supported=True, **effort)))

    class Models:
        async def retrieve(self, model: str) -> SimpleNamespace:
            if model == "boom":
                raise RuntimeError("no network")
            return capability(low=True, medium=False, high=True, max=True, xhigh=None)

    llm, _ = llm_with(make_settings(tmp_path), [])
    llm._client = SimpleNamespace(models=Models())  # type: ignore[assignment]
    assert await llm.efforts("claude-x") == ModelEfforts(("low", "high", "max"), "low")  # medium missing -> nearest, ties go lower
    assert (await llm.efforts("boom")).efforts == ("low", "medium", "high", "max")


class Recorder:
    """A provider double: records stream options, probes as scripted."""

    def __init__(self, name: str, info: ModelEfforts, probe_error: LLMError | None = None) -> None:
        self.name, self.info, self.probe_error = name, info, probe_error
        self.probes = 0
        self.sent: dict[str, Any] = {}

    async def usage(self) -> UsageLeft | None:
        return UsageLeft(0.25, "$5.00") if self.name == "anthropic" else None

    async def probe(self) -> None:
        self.probes += 1
        if self.probe_error:
            raise self.probe_error

    async def efforts(self, model: str) -> ModelEfforts:
        return self.info

    async def stream(self, messages: Any, model: str, **options: Any) -> Any:
        self.sent = {"model": model, **options}
        yield self.name


def multi_with(tmp_path: Path, **providers: Recorder) -> MultiLLM:
    settings = make_settings(tmp_path, openrouter_api_key="o", kimi_api_key="k")
    return MultiLLM(settings, providers)  # type: ignore[arg-type]


async def test_multi_llm_routes_by_model_and_clamps_effort(tmp_path: Path) -> None:
    claude = Recorder("anthropic", ModelEfforts(("low", "medium", "high"), "medium"))
    kimi = Recorder("kimi", ModelEfforts())
    multi = multi_with(tmp_path, anthropic=claude, kimi=kimi)
    assert [i async for i in multi.stream([], "claude-haiku-5-5", effort="max")] == ["anthropic"]
    assert claude.sent["effort"] == "high"  # max clamped to what the model accepts
    [i async for i in multi.stream([], "claude-haiku-5-5")]
    assert claude.sent["effort"] == "medium"  # model default
    assert [i async for i in multi.stream([], "k3.1-pro", effort="high")] == ["kimi"]
    assert kimi.sent == {"model": "k3.1-pro", "effort": None, "web_search": None}  # not adjustable
    with pytest.raises(LLMError):
        [i async for i in multi.stream([], "unknown-model")]


async def test_multi_llm_offers_only_working_providers(tmp_path: Path) -> None:
    good = Recorder("anthropic", ModelEfforts())
    bad = Recorder("kimi", ModelEfforts(), probe_error=LLMError("upstream_auth", "invalid api key"))
    multi = multi_with(tmp_path, anthropic=good, kimi=bad)
    default, models = await multi.models()
    assert default == "claude-haiku-5-5"
    assert "k3.1-pro" not in models and "claude-sonnet-5-5" in models
    info = await multi.providers_info()
    assert info["anthropic"]["ok"] is True and "claude-sonnet-5-5" in info["anthropic"]["models"]
    assert info["anthropic"]["usage"] == {"remaining": 0.25, "label": "$5.00"}
    assert info["kimi"] == {"ok": False, "reason": "upstream_auth", "models": [], "usage": None}
    with pytest.raises(LLMError) as down:  # a model of a failing provider is refused with the probe's reason
        [i async for i in multi.stream([], "k3.1-pro")]
    assert down.value.code == "upstream_auth" and bad.sent == {}


async def test_multi_llm_default_follows_the_first_working_provider(tmp_path: Path) -> None:
    down = Recorder("anthropic", ModelEfforts(), probe_error=LLMError("upstream_error", "unreachable"))
    kimi = Recorder("kimi", ModelEfforts())
    multi = multi_with(tmp_path, anthropic=down, kimi=kimi)
    assert (await multi.models())[0] == "k3.1-pro"
    none = multi_with(tmp_path, anthropic=down)
    assert await none.models() == ("", [])


async def test_multi_llm_caches_probes_and_recovers(tmp_path: Path) -> None:
    flaky = Recorder("kimi", ModelEfforts(), probe_error=LLMError("upstream_error", "timeout"))
    multi = multi_with(tmp_path, kimi=flaky)
    await multi.models()
    await multi.models()
    assert flaky.probes == 1  # cached
    flaky.probe_error = None
    multi._health["kimi"] = Health(False, "upstream_error", "timeout", until=0.0)  # the retry window has passed
    assert (await multi.models())[1] == ["k3.1-pro", "k3", "kimi-for-coding"]
    assert flaky.probes == 2


async def test_auth_failure_during_chat_disables_the_provider(tmp_path: Path) -> None:
    class Rejected(Recorder):
        async def stream(self, messages: Any, model: str, **options: Any) -> Any:
            raise LLMError("upstream_auth", "key revoked")
            yield  # pragma: no cover

    multi = multi_with(tmp_path, kimi=Rejected("kimi", ModelEfforts()))
    with pytest.raises(LLMError):
        [i async for i in multi.stream([], "k3.1-pro")]
    assert (await multi.models()) == ("", [])


def test_kimi_body(tmp_path: Path) -> None:
    settings = make_settings(tmp_path, kimi_api_key="k")
    llm = KimiLLM(settings, httpx.AsyncClient())
    body = llm._body([{"role": "user", "content": "hi"}], "k3.1-pro")
    assert body["max_tokens"] == 8192 and body["stream_options"] == {"include_usage": True}
    assert "tools" not in body and "reasoning_effort" not in body
    searching = llm._body([], "m", effort="high", web_search=True)
    assert searching["tools"] == [{"type": "builtin_function", "function": {"name": "$web_search"}}]
    assert searching["reasoning_effort"] == "high"
    assert llm._body([], "m", effort="none")["thinking"] == {"type": "disabled"}


async def test_kimi_code_efforts_follow_the_docs_per_model(tmp_path: Path) -> None:
    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), httpx.AsyncClient())
    assert await llm.efforts("k3") == ModelEfforts(("low", "high", "max"), "")
    assert await llm.efforts("kimi-for-coding") == ModelEfforts(("low", "high", "max"), "")
    assert await llm.efforts("k3.1-pro") == ModelEfforts()  # thinking is always on, at max
    assert await llm.efforts("k3.1-pro-highspeed") == ModelEfforts()  # undocumented: not adjustable


async def test_kimi_per_model_override_and_global_fallback(tmp_path: Path) -> None:
    llm = KimiLLM(
        make_settings(
            tmp_path,
            kimi_api_key="k",
            kimi_model_efforts={"k3": "low, high", "kimi-for-coding": ""},
            kimi_efforts="medium",
            kimi_default_effort="high",
        ),
        httpx.AsyncClient(),
    )
    assert await llm.efforts("k3") == ModelEfforts(("low", "high"), "high")
    assert await llm.efforts("kimi-for-coding") == ModelEfforts()  # an empty override turns effort off
    assert await llm.efforts("other") == ModelEfforts(("medium",), "medium")  # the global list for the rest


KIMI_CATALOG = {
    "data": [
        {"id": "moonshotai/k4", "reasoning": {"supported_efforts": ["max", "high", "low", "none"], "default_effort": "max"}},
        {"id": "moonshotai/k2", "reasoning": {"mandatory": False, "default_enabled": True}},
    ]
}


async def test_kimi_catalog_is_opt_in(tmp_path: Path) -> None:
    client = mock_client(lambda request: httpx.Response(200, json=KIMI_CATALOG))
    off = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), client)
    assert await off.efforts("k4") == ModelEfforts()  # not in the per-model table, catalog off
    on = KimiLLM(make_settings(tmp_path, kimi_api_key="k", kimi_efforts_from_catalog=True), client)
    assert await on.efforts("k4") == ModelEfforts(("none", "low", "high", "max"), "max")
    assert await on.efforts("k2") == ModelEfforts()  # known, but only on/off


def test_kimi_model_efforts_parse_from_json_env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("KIMI_MODEL_EFFORTS", '{"k3.1-pro": "low,high,max", "k3": ""}')
    settings = Settings(_env_file=None, data_dir=tmp_path)
    assert settings.kimi_model_efforts == {"k3.1-pro": "low,high,max", "k3": ""}


async def test_kimi_runs_the_web_search_tool_loop(tmp_path: Path) -> None:
    requests: list[dict[str, Any]] = []
    first = sse(
        {"choices": [{"delta": {"reasoning_content": "Need data. "}}]},
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_1", "function": {"name": "$web_search", "arguments": ""}}]}}]},
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"q\":\"rain\"}"}}]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls", "usage": {"prompt_tokens": 20, "completion_tokens": 4}}]},
    )
    second = sse(
        {"choices": [{"delta": {"content": "Rain "}}]},
        {"choices": [{"delta": {"content": "tomorrow."}, "finish_reason": "stop"}]},
        {"choices": [], "usage": {"prompt_tokens": 50, "completion_tokens": 6}},
    )
    scripted = [first, second]

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, content=scripted.pop(0), headers={"content-type": "text/event-stream"})

    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), mock_client(handler))
    items = [i async for i in llm.stream([{"role": "user", "content": "weather?"}], "k3.1-pro", web_search=True)]
    assert items == [Thinking("Need data. "), "Rain ", "tomorrow.", Usage(prompt_tokens=70, completion_tokens=10)]
    assert requests[0]["tools"][0]["function"]["name"] == "$web_search"
    followup = requests[1]["messages"]
    assert followup[-2]["role"] == "assistant"
    assert followup[-2]["reasoning_content"] == "Need data. "
    assert followup[-2]["tool_calls"][0]["function"] == {"name": "$web_search", "arguments": '{"q":"rain"}'}
    assert followup[-1] == {"role": "tool", "tool_call_id": "call_1", "name": "$web_search", "content": '{"q":"rain"}'}


async def test_kimi_withholds_search_once_the_budget_is_used(tmp_path: Path) -> None:
    requests: list[dict[str, Any]] = []
    search = sse(
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "c", "function": {"name": "$web_search", "arguments": "{}"}}]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    )
    answer = sse({"choices": [{"delta": {"content": "ok"}, "finish_reason": "stop"}]})
    scripted = [search, answer]

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, content=scripted.pop(0))

    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k", web_search_max_uses=1), mock_client(handler))
    assert [i async for i in llm.stream([{"role": "user", "content": "q"}], "m")] == ["ok"]
    assert "tools" in requests[0] and "tools" not in requests[1]


async def test_kimi_errors_and_missing_key(tmp_path: Path) -> None:
    keyless = KimiLLM(make_settings(tmp_path), httpx.AsyncClient())
    with pytest.raises(LLMError) as missing:
        [i async for i in keyless.stream([], "m")]
    assert missing.value.code == "upstream_auth"

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "slow down"}})

    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), mock_client(handler))
    with pytest.raises(LLMError) as limited:
        [i async for i in llm.stream([{"role": "user", "content": "q"}], "m")]
    assert limited.value.code == "upstream_rate_limited" and limited.value.message == "slow down"


def test_upstream_errors_are_classified() -> None:
    assert error_code_for_status(402) == "upstream_quota"
    assert error_code_for_status(400, "Your credit balance is too low to access the API") == "upstream_quota"
    assert error_code_for_status(429, "exceeded_current_quota_error") == "upstream_quota"
    assert error_code_for_status(429, "Rate limit reached") == "upstream_rate_limited"
    assert error_code_for_status(401) == "upstream_auth"
    assert error_code_for_status(503) == "upstream_error"
    assert parse_retry_after("12") == 12 and parse_retry_after("soon") is None and parse_retry_after(None) is None


async def test_providers_probe_their_key_endpoint(tmp_path: Path) -> None:
    seen: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((str(request.url), request.headers["authorization"]))
        status = {"https://openrouter.ai/api/v1/key": 200, "https://api.kimi.ai/coding/v1/models": 401}[str(request.url)]
        return httpx.Response(status, json={"error": {"message": "bad key"}} if status >= 400 else {})

    client = mock_client(handler)
    settings = make_settings(tmp_path, openrouter_api_key="or-key", kimi_api_key="kimi-key")
    await OpenRouterLLM(settings, client).probe()
    with pytest.raises(LLMError) as rejected:
        await KimiLLM(settings, client).probe()
    assert rejected.value.code == "upstream_auth"
    assert seen == [
        ("https://openrouter.ai/api/v1/key", "Bearer or-key"),
        ("https://api.kimi.ai/coding/v1/models", "Bearer kimi-key"),
    ]


async def test_probe_treats_rate_limiting_as_working_and_network_errors_as_down(tmp_path: Path) -> None:
    settings = make_settings(tmp_path, kimi_api_key="k")
    await KimiLLM(settings, mock_client(lambda r: httpx.Response(429))).probe()

    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route", request=request)

    with pytest.raises(LLMError) as down:
        await KimiLLM(settings, mock_client(refuse)).probe()
    assert down.value.code == "upstream_error"


async def test_kimi_quota_error_carries_retry_after(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            429, json={"error": {"message": "exceeded_current_quota_error"}}, headers={"Retry-After": "90"}
        )

    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), mock_client(handler))
    with pytest.raises(LLMError) as quota:
        [i async for i in llm.stream([{"role": "user", "content": "q"}], "m")]
    assert quota.value.code == "upstream_quota" and quota.value.retry_after == 90


async def test_openrouter_usage_from_key_limit_then_credits(tmp_path: Path) -> None:
    settings = make_settings(tmp_path, openrouter_api_key="o")
    limited = {"data": {"limit": 20, "limit_remaining": 5}}
    llm = OpenRouterLLM(settings, mock_client(lambda r: httpx.Response(200, json=limited)))
    assert await llm.usage() == UsageLeft(0.25, "$5.00")

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/key"):
            return httpx.Response(200, json={"data": {"limit": None, "limit_remaining": None}})
        return httpx.Response(200, json={"data": {"total_credits": 10.0, "total_usage": 7.5}})

    assert await OpenRouterLLM(settings, mock_client(handler)).usage() == UsageLeft(0.25, "$2.50")
    assert await OpenRouterLLM(settings, mock_client(lambda r: httpx.Response(500, text="x"))).usage() is None


async def test_kimi_code_usage_from_the_usages_endpoint(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/coding/v1/usages")
        return httpx.Response(200, json={"usage": {"limit": "100", "remaining": "25"}})

    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), mock_client(handler))
    assert await llm.usage() == UsageLeft(0.25, "25%")
    assert UsageLeft(1.7, "x").to_json()["remaining"] == 1.0  # clamped for the ring
    # Undocumented endpoint: anything unexpected just means no ring.
    assert await KimiLLM(make_settings(tmp_path, kimi_api_key="k"), mock_client(lambda r: httpx.Response(404))).usage() is None
    odd = mock_client(lambda r: httpx.Response(200, json={"something": "else"}))
    assert await KimiLLM(make_settings(tmp_path, kimi_api_key="k"), odd).usage() is None


async def test_kimi_probe_tolerates_a_missing_models_endpoint(tmp_path: Path) -> None:
    settings = make_settings(tmp_path, kimi_api_key="k")
    await KimiLLM(settings, mock_client(lambda r: httpx.Response(404))).probe()  # reachable, key not rejected
    with pytest.raises(LLMError) as rejected:
        await KimiLLM(settings, mock_client(lambda r: httpx.Response(401, json={"error": {"message": "bad"}}))).probe()
    assert rejected.value.code == "upstream_auth"


async def test_kimi_drops_web_search_when_the_endpoint_rejects_the_tool(tmp_path: Path) -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if "tools" in body:
            return httpx.Response(400, json={"error": {"message": "tools are not supported"}})
        return httpx.Response(200, content=sse({"choices": [{"delta": {"content": "ok"}, "finish_reason": "stop"}]}))

    llm = KimiLLM(make_settings(tmp_path, kimi_api_key="k"), mock_client(handler))
    assert [i async for i in llm.stream([{"role": "user", "content": "q"}], "k3")] == ["ok"]
    assert "tools" in requests[0] and "tools" not in requests[1]
    # Remembered: the next question does not try the tool again.
    scripted = len(requests)
    assert [i async for i in llm.stream([{"role": "user", "content": "q2"}], "k3")] == ["ok"]
    assert len(requests) == scripted + 1 and "tools" not in requests[-1]
