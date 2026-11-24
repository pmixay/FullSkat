"""The draw tool: validation, the tool loops of every provider and how figures reach the watch."""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.figures import FigureError, fenced_block, normalize_figure
from fullskat_server.llm import Figure, KimiLLM, OpenAILLM, OpenRouterLLM, Thinking, Usage
from fullskat_server.prompts import build_system_prompt

from helpers import FakeLLM, parse_sse
from helpers import token_for as pair
from test_llm import final_message, llm_with

SINE = {"title": "y = sin x", "x_range": [-6.2832, 6.2832], "items": [{"type": "function", "expr": "sin(x)", "label": "$\\sin x$"}]}


def settings(tmp_path: Path, **overrides: Any) -> Settings:
    base = {"anthropic_api_key": "a", "openrouter_api_key": "o", "kimi_api_key": "k", "openai_api_key": "g",
            "openai_base_url": "http://gateway/v1"}
    return Settings(_env_file=None, data_dir=tmp_path, **{**base, **overrides})


# ---- validation --------------------------------------------------------------


def test_normalize_keeps_known_fields_and_rounds_numbers() -> None:
    figure, note = normalize_figure({
        "title": "  Parabola  ", "x_range": [-3.14159265, 3.14159265], "y_range": [5, 1], "axes": True, "junk": 1,
        "items": [
            {"type": "function", "expr": "x^2 - 2", "color": "BLUE", "area": [0, 1], "extra": "drop me"},
            {"type": "point", "at": [1.0, -1.0], "label": "A", "fill": False, "dash": True},
        ],
    })
    assert figure == {
        "title": "Parabola", "x_range": [-3.14159, 3.14159], "axes": True,  # y_range dropped: min > max
        "items": [
            {"type": "function", "expr": "x^2 - 2", "area": [0, 1], "color": "blue"},
            {"type": "point", "at": [1, -1], "label": "A", "dash": True},
        ],
    }
    assert note.startswith("The figure is on the watch screen")


def test_normalize_skips_broken_items_and_tells_the_model() -> None:
    figure, note = normalize_figure(json.dumps({"items": [
        {"type": "function", "expr": "erf(x)"},
        {"type": "circle", "center": [0, 0], "r": 2},
        {"type": "teapot"},
    ]}))
    assert figure["items"] == [{"type": "circle", "center": [0, 0], "r": 2}]
    assert "items[0] (function): expr: unknown name 'erf'" in note
    assert "items[2] (teapot): unknown type" in note


@pytest.mark.parametrize(("raw", "message"), [
    ("not json", "not valid JSON"),
    ({"items": []}, "non-empty"),
    ({"items": [{"type": "function", "expr": "sin(x"}]}, "unbalanced parentheses"),
    ({"items": [{"type": "function", "expr": "sin(t)"}]}, "the variable is x"),
    ({"items": [{"type": "pie", "values": [-1, 2]}]}, "non-negative"),
    ({"items": [{"type": "path", "d": "M 0 0 <script>"}]}, "SVG path data"),
    ({"items": [{"type": "segment", "from": [0, 0], "to": [1, float("nan")]}]}, "finite"),
])
def test_normalize_rejects_what_cannot_be_drawn(raw: Any, message: str) -> None:
    with pytest.raises(FigureError, match=message):
        normalize_figure(raw)


def test_expressions_accept_the_watch_syntax() -> None:
    items = [
        {"type": "function", "expr": "2x^2 - 3|x| + e^(-x) * sqrt(abs(x)) + if(x < 0, -1, 1) + log10(x) ** 2"},
        {"type": "parametric", "x": "cos(3t)", "y": "sin(2t)", "t": [0, 6.283]},
        {"type": "polar", "expr": "1 + cos(θ)"},
        {"type": "polar", "r": "2 sin(4t)"},  # r as an alias of expr
    ]
    figure, _ = normalize_figure({"items": items})
    assert [i.get("expr") for i in figure["items"]][2:] == ["1 + cos(θ)", "2 sin(4t)"]


def test_fenced_block_is_one_json_line_that_cannot_close_the_fence() -> None:
    block = fenced_block({"title": "a ``` b", "items": [{"type": "text", "at": [0, 0], "text": "Привет"}]})
    lines = block.split("\n")
    assert lines[0] == "```figure" and lines[2] == "```" and len(lines) == 3
    assert "`" not in lines[1] and "Привет" in lines[1]
    assert json.loads(lines[1])["title"] == "a ``` b"


def test_prompt_mentions_drawing_only_when_offered() -> None:
    assert "`draw` tool" in build_system_prompt("ru-RU", figures=True)
    assert "DRAWING" not in build_system_prompt("ru-RU")


# ---- Claude --------------------------------------------------------------------


def tool_use(input_: Any, id_: str = "toolu_1", name: str = "draw") -> SimpleNamespace:
    return SimpleNamespace(type="tool_use", id=id_, name=name, input=input_)


async def test_anthropic_draws_and_continues(tmp_path: Path) -> None:
    first = final_message("tool_use", content=[SimpleNamespace(type="text", text="Вот:"), tool_use(SINE)], inp=30, out=40)
    llm, fake = llm_with(settings(tmp_path), [(["Вот:"], first), (["Период 2π."], final_message(inp=50, out=5))])
    items = [i async for i in llm.stream([{"role": "user", "content": "нарисуй синус"}], "claude-haiku-5-5")]
    assert items == ["Вот:", Figure(normalize_figure(SINE)[0]), "Период 2π.", Usage(80, 45)]
    assert [t["name"] for t in fake.requests[0]["tools"]] == ["web_search", "draw"]
    assert "input_schema" in fake.requests[0]["tools"][1]
    followup = fake.requests[1]["messages"]
    assert followup[-2] == {"role": "assistant", "content": first.content}
    result = followup[-1]["content"][0]
    assert result["type"] == "tool_result" and result["tool_use_id"] == "toolu_1" and "is_error" not in result
    assert "tool_choice" not in fake.requests[1]


async def test_anthropic_reports_bad_figures_and_stops_offering_after_the_cap(tmp_path: Path) -> None:
    bad = final_message("tool_use", content=[tool_use({"items": []}, "t1")])
    good = final_message("tool_use", content=[tool_use(SINE, "t2")])
    llm, fake = llm_with(settings(tmp_path, figures_max_per_answer=2), [([], bad), ([], good), (["ok"], final_message())])
    items = [i async for i in llm.stream([{"role": "user", "content": "q"}], "claude-haiku-5-5")]
    assert [type(i).__name__ for i in items] == ["Figure", "str", "Usage"]
    error = fake.requests[1]["messages"][-1]["content"][0]
    assert error["is_error"] is True and "Not drawn" in error["content"]
    assert "tool_choice" not in fake.requests[1]
    assert fake.requests[2]["tool_choice"] == {"type": "none"}  # two calls used up


async def test_anthropic_without_figures_offers_no_draw_tool(tmp_path: Path) -> None:
    llm, fake = llm_with(settings(tmp_path), [(["hi"], final_message())])
    [i async for i in llm.stream([{"role": "user", "content": "q"}], "claude-haiku-5-5", figures=False)]
    assert [t["name"] for t in fake.requests[0]["tools"]] == ["web_search"]
    llm, fake = llm_with(settings(tmp_path, figures=False, web_search=False), [(["hi"], final_message())])
    [i async for i in llm.stream([{"role": "user", "content": "q"}], "claude-haiku-5-5", figures=True)]
    assert "tools" not in fake.requests[0]  # FIGURES is a master switch


# ---- chat completions (OpenRouter, Kimi) ------------------------------------------


def sse(*chunks: Any) -> bytes:
    return ("".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n").encode()


def draw_call(arguments: str, index: int = 0, id_: str = "call_d") -> list[dict[str, Any]]:
    return [
        {"choices": [{"delta": {"tool_calls": [{"index": index, "id": id_, "function": {"name": "draw", "arguments": ""}}]}}]},
        {"choices": [{"delta": {"tool_calls": [{"index": index, "function": {"arguments": arguments}}]}}]},
    ]


def scripted(*responses: bytes) -> tuple[httpx.AsyncClient, list[dict[str, Any]]]:
    requests: list[dict[str, Any]] = []
    queue = list(responses)

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, content=queue.pop(0), headers={"content-type": "text/event-stream"})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler)), requests


async def test_openrouter_draws_next_to_the_web_search_server_tool(tmp_path: Path) -> None:
    first = sse(
        {"choices": [{"delta": {"content": "Graph:"}}]},
        *draw_call(json.dumps(SINE)),
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}], "usage": {"prompt_tokens": 9, "completion_tokens": 30}},
    )
    second = sse({"choices": [{"delta": {"content": " done"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 40, "completion_tokens": 2}})
    client, requests = scripted(first, second)
    llm = OpenRouterLLM(settings(tmp_path), client)
    items = [i async for i in llm.stream([{"role": "user", "content": "q"}], "openai/gpt-6.1-terra")]
    assert items == ["Graph:", Figure(normalize_figure(SINE)[0]), " done", Usage(49, 32)]
    assert [t["type"] for t in requests[0]["tools"]] == ["openrouter:web_search", "function"]
    assert requests[0]["tools"][1]["function"]["name"] == "draw"
    assert requests[1]["tools"] == requests[0]["tools"]  # the model decides whether to search again
    assistant, tool = requests[1]["messages"][-2:]
    assert assistant["content"] == "Graph:"
    assert assistant["tool_calls"][0]["function"]["name"] == "draw"
    assert tool["role"] == "tool" and tool["tool_call_id"] == "call_d" and tool["content"].startswith("The figure")


async def test_openrouter_sets_tool_choice_none_after_the_cap(tmp_path: Path) -> None:
    call = sse(*draw_call(json.dumps(SINE)), {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]})
    done = sse({"choices": [{"delta": {"content": "ok"}, "finish_reason": "stop"}]})
    client, requests = scripted(call, done)
    llm = OpenRouterLLM(settings(tmp_path, figures_max_per_answer=1), client)
    items = [i async for i in llm.stream([{"role": "user", "content": "q"}], "openai/gpt-6.1-terra", figures=True)]
    assert items[-1] == "ok"
    assert "tool_choice" not in requests[0] and requests[1]["tool_choice"] == "none"


async def test_kimi_answers_search_and_draw_in_one_loop(tmp_path: Path) -> None:
    first = sse(
        {"choices": [{"delta": {"reasoning_content": "Plan. "}}]},
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "s1", "function": {"name": "$web_search", "arguments": "{\"q\":1}"}}]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    )
    second = sse(*draw_call(json.dumps(SINE), id_="d1"), {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]})
    third = sse({"choices": [{"delta": {"content": "Готово."}, "finish_reason": "stop"}]})
    client, requests = scripted(first, second, third)
    llm = KimiLLM(settings(tmp_path, web_search_max_uses=1, figures_max_per_answer=1), client)
    items = [i async for i in llm.stream([{"role": "user", "content": "q"}], "k3.1-pro", web_search=True)]
    assert items == [Thinking("Plan. "), Figure(normalize_figure(SINE)[0]), "Готово."]
    assert [t["function"]["name"] for t in requests[0]["tools"]] == ["$web_search", "draw"]
    assert [t["function"]["name"] for t in requests[1]["tools"]] == ["draw"]  # search budget used
    assert "tools" not in requests[2]  # draw budget used too: withheld
    assert requests[1]["messages"][-2]["reasoning_content"] == "Plan. "
    assert requests[2]["messages"][-1] == {"role": "tool", "tool_call_id": "d1", "name": "draw",
                                           "content": requests[2]["messages"][-1]["content"]}


async def test_chat_completions_stop_at_unknown_tools(tmp_path: Path) -> None:
    odd = sse(
        {"choices": [{"delta": {"content": "Hm"}}]},
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "x", "function": {"name": "teleport", "arguments": "{}"}}]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    )
    client, requests = scripted(odd)
    llm = OpenRouterLLM(settings(tmp_path), client)
    assert [i async for i in llm.stream([{"role": "user", "content": "q"}], "openai/gpt-6.1-terra")] == ["Hm"]
    assert len(requests) == 1


# ---- OpenAI Responses -----------------------------------------------------------


def responses_sse(*events: dict) -> bytes:
    return "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()


def completed(inp: int, out: int, output: list[dict] | None = None) -> dict:
    return {"type": "response.completed", "response": {"usage": {"input_tokens": inp, "output_tokens": out}, "output": output or []}}


async def test_openai_responses_function_call_loop(tmp_path: Path) -> None:
    call = {"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "draw", "arguments": json.dumps(SINE)}
    first = responses_sse(
        {"type": "response.output_text.delta", "delta": "Here:"},
        {"type": "response.output_item.done", "item": call},
        completed(10, 20, [call]),
    )
    second = responses_sse({"type": "response.output_text.delta", "delta": " sine."}, completed(30, 3))
    client, requests = scripted(first, second)
    llm = OpenAILLM(settings(tmp_path), client)
    items = [i async for i in llm.stream([{"role": "system", "content": "S"}, {"role": "user", "content": "q"}], "gpt-6.1-terra")]
    assert items == ["Here:", Figure(normalize_figure(SINE)[0]), " sine.", Usage(40, 23)]
    assert [t["type"] for t in requests[0]["tools"]] == ["web_search", "function"]
    assert requests[0]["tools"][1]["name"] == "draw"
    follow = requests[1]["input"]
    assert follow[0] == {"role": "user", "content": "q"}
    assert follow[1] == {"role": "assistant", "content": "Here:"}
    assert follow[2] == {"type": "function_call", "call_id": "call_1", "name": "draw", "arguments": json.dumps(SINE)}
    assert follow[3]["type"] == "function_call_output" and follow[3]["call_id"] == "call_1"
    assert "id" not in follow[2]  # store=false: no reasoning item to pair an fc_ id with
    assert requests[1]["instructions"] == "S"


async def test_openai_responses_cap_sets_tool_choice_none(tmp_path: Path) -> None:
    call = {"type": "function_call", "call_id": "c", "name": "draw", "arguments": "{\"items\": []}"}
    first = responses_sse({"type": "response.output_item.done", "item": call}, completed(1, 1))
    second = responses_sse({"type": "response.output_text.delta", "delta": "words"}, completed(1, 1))
    client, requests = scripted(first, second)
    llm = OpenAILLM(settings(tmp_path, figures_max_per_answer=1), client)
    items = [i async for i in llm.stream([{"role": "user", "content": "q"}], "gpt-6.1-terra")]
    assert items == ["words", Usage(2, 2)]  # the broken figure is not shown
    assert requests[1]["tool_choice"] == "none"
    assert requests[1]["input"][-1]["output"].startswith("Not drawn")


# ---- the watch protocol -----------------------------------------------------------


async def test_figures_stream_as_fenced_blocks_between_paragraphs(tmp_path: Path) -> None:
    app = create_app(settings=settings(tmp_path))
    figure = normalize_figure(SINE)[0]
    app.state.llm = FakeLLM(deltas=["Смотри:", Figure(figure), "\n", "Это синус."])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await pair(client)
        response = await client.post("/v1/chat", json={"messages": [{"role": "user", "content": "q"}]},
                                     headers={"Authorization": f"Bearer {token}"})
        events = parse_sse(response.text)
        done = events[-1][1]["text"]
        assert done == "Смотри:\n\n" + fenced_block(figure) + "\n\nЭто синус."
        assert [e[1]["text"] for e in events if e[0] == "delta"][1].startswith("\n\n```figure\n")
        messages, _ = app.state.llm.requests[-1]
        assert "`draw` tool" in messages[0]["content"]
        assert app.state.llm.figure_flags[-1] is None

        await client.post("/v1/chat", json={"messages": [{"role": "user", "content": "q"}], "figures": False},
                          headers={"Authorization": f"Bearer {token}"})
        messages, _ = app.state.llm.requests[-1]
        assert "`draw` tool" not in messages[0]["content"]
        assert app.state.llm.figure_flags[-1] is False


async def test_figure_first_in_the_answer_has_no_leading_blank_lines(tmp_path: Path) -> None:
    app = create_app(settings=settings(tmp_path))
    figure = normalize_figure(SINE)[0]
    app.state.llm = FakeLLM(deltas=[Figure(figure), "Синус."])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await pair(client)
        response = await client.post("/v1/chat", json={"messages": [{"role": "user", "content": "q"}]},
                                     headers={"Authorization": f"Bearer {token}"})
        assert parse_sse(response.text)[-1][1]["text"] == fenced_block(figure) + "\n\nСинус."


async def test_server_switch_turns_the_draw_prompt_off(tmp_path: Path) -> None:
    app = create_app(settings=settings(tmp_path, figures=False))
    app.state.llm = FakeLLM(deltas=["x"])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await pair(client)
        await client.post("/v1/chat", json={"messages": [{"role": "user", "content": "q"}], "figures": True},
                          headers={"Authorization": f"Bearer {token}"})
        messages, _ = app.state.llm.requests[-1]
        assert "`draw` tool" not in messages[0]["content"]
        me = await client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
        assert me.json()["figures"] is False
