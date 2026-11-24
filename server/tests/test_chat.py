"""Chat v2: answers generated on the server, saved while they stream, independent of the connection."""

import asyncio
from pathlib import Path

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.llm import Figure, LLMError, Thinking
from helpers import AsgiStream, FakeLLM, bearer, chat_body, parse_sse, register, token_for


async def sync(client: AsyncClient, token: str, since: int = 0) -> dict:
    return (await client.get("/v1/sync", params={"since": since}, headers=bearer(token))).json()


async def wait_until_finished(app: FastAPI, answer_id: str) -> None:
    answer = app.state.answers.get(answer_id)
    if answer is not None:
        await answer.wait(5)


async def test_chat_saves_the_question_and_the_answer(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    response = await client.post("/v1/chat", json=chat_body("Какая завтра погода?", locale="ru-RU"),
                                 headers={**bearer(token), "Accept": "text/event-stream"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["thinking", "thinking", "delta", "delta", "delta", "done"]
    done = events[-1][1]
    assert done["text"] == "Hello world" and done["model"] == "claude-haiku-5-5"
    assert done["usage"] == {"prompt_tokens": 10, "completion_tokens": 3}
    assert done["thinking"] == "Greeting the user." and done["sources"] == []
    message = done["message"]
    assert message["id"] == "a1" and message["dialog_id"] == "d1" and message["role"] == "assistant"
    assert message["content"] == "Hello world" and message["thinking"] == "Greeting the user."
    assert message["status"] == "done" and message["error"] == "" and message["model"] == "claude-haiku-5-5"
    assert message["deleted"] is False and message["attachments"] == [] and message["sources"] == []
    assert done["seq"] == message["seq"]

    synced = await sync(client, token)
    assert synced["seq"] == done["seq"]
    question, answer = synced["messages"]
    assert (question["id"], question["role"], question["content"], question["status"]) == \
        ("m1", "user", "Какая завтра погода?", "done")
    assert answer == message
    assert answer["created_at"] > question["created_at"]
    (dialog,) = synced["dialogs"]
    assert dialog["id"] == "d1" and dialog["title"] == "Какая завтра погода?"  # titled from the first question
    assert question["seq"] < answer["seq"]


async def test_title_is_the_first_60_characters_and_stays(client: AsyncClient) -> None:
    token = await token_for(client)
    long = "  Расскажи   подробно, " + "очень " * 20
    await client.post("/v1/chat", json=chat_body(long), headers=bearer(token))
    await client.post("/v1/chat", json=chat_body("second", message_id="m2", answer_id="a2"), headers=bearer(token))
    title = (await sync(client, token))["dialogs"][0]["title"]
    assert title == " ".join(long.split())[:60]


async def test_chat_into_an_existing_dialog_with_an_empty_title(client: AsyncClient) -> None:
    token = await token_for(client)
    await client.post("/v1/dialogs", json={"id": "d1"}, headers=bearer(token))
    await client.post("/v1/chat", json=chat_body("Вопрос"), headers=bearer(token))
    assert (await sync(client, token))["dialogs"][0]["title"] == "Вопрос"


async def test_history_comes_from_the_server(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    await client.post("/v1/chat", json=chat_body("q1"), headers=bearer(token))
    app.state.llm = FakeLLM(error=LLMError("upstream_error", "boom"))  # an error with no text is left out
    await client.post("/v1/chat", json=chat_body("q2", message_id="m2", answer_id="a2"), headers=bearer(token))
    app.state.llm = FakeLLM(deltas=["Partial"], error=LLMError("upstream_error", "cut"), error_after=1)
    await client.post("/v1/chat", json=chat_body("q3", message_id="m3", answer_id="a3"), headers=bearer(token))
    await client.post("/v1/dialogs", json={"id": "other"}, headers=bearer(token))
    app.state.llm = FakeLLM(deltas=["x"])
    await client.post("/v1/chat", json=chat_body("elsewhere", dialog_id="other", message_id="o1", answer_id="o2"),
                      headers=bearer(token))
    await client.delete("/v1/messages/m3", headers=bearer(token))

    app.state.llm = FakeLLM(deltas=["ok"])
    await client.post("/v1/chat", json=chat_body("q4", message_id="m4", answer_id="a4", locale="en-US"),
                      headers=bearer(token))
    (messages, _), = app.state.llm.requests
    assert messages[0]["role"] == "system" and "User locale: en-US" in messages[0]["content"]
    assert messages[1:] == [
        {"role": "user", "content": "q1"},
        {"role": "assistant", "content": "Hello world"},
        {"role": "user", "content": "q2"},
        {"role": "assistant", "content": "Partial"},  # an error answer keeps what arrived
        {"role": "user", "content": "q4"},
    ]


async def test_history_is_trimmed_to_the_newest_messages(tmp_path: Path) -> None:
    app = create_app(Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k", max_history_messages=4))
    app.state.llm = FakeLLM(deltas=["a"])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await token_for(client)
        for i in range(4):
            await client.post("/v1/chat", json=chat_body(f"q{i}", message_id=f"m{i}", answer_id=f"a{i}"),
                              headers=bearer(token))
        sent = app.state.llm.requests[-1][0]
        assert [m["content"] for m in sent[1:]] == ["a", "q2", "a", "q3"]  # the 4 newest, not the streaming answer


async def test_prompt_depends_on_the_asking_device(client: AsyncClient, app: FastAPI) -> None:
    phone = await token_for(client)
    await client.post("/v1/chat", json=chat_body(), headers=bearer(phone))
    prompt = app.state.llm.requests[-1][0][0]["content"]
    assert "phone app" in prompt and "150 words" in prompt and "smartwatch" not in prompt
    watch = await token_for(client, device_id="watch-x", device_kind="watch", name="W")
    await client.post("/v1/chat", json=chat_body(message_id="m2", answer_id="a2", dialog_id="d2"), headers=bearer(watch))
    prompt = app.state.llm.requests[-1][0][0]["content"]
    assert "smartwatch" in prompt and "60 words" in prompt


async def test_upstream_error_is_saved_with_the_text_so_far(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    app.state.llm = FakeLLM(deltas=["Half ", "an answer"], error=LLMError("upstream_quota", "no credits", 90),
                            error_after=2)
    events = parse_sse((await client.post("/v1/chat", json=chat_body(), headers=bearer(token))).text)
    assert events[-1] == ("error", {"error": "upstream_quota", "message": "no credits", "retry_after": 90})
    answer = (await sync(client, token))["messages"][-1]
    assert (answer["status"], answer["error"], answer["content"]) == ("error", "upstream_quota", "Half an answer")


async def test_figures_and_sources_are_saved_with_the_answer(client: AsyncClient, app: FastAPI) -> None:
    from fullskat_server.figures import fenced_block, normalize_figure
    from fullskat_server.sources import Source, Sources

    figure = normalize_figure({"items": [{"type": "function", "expr": "sin(x)"}], "x_range": [0, 6]})[0]
    app.state.llm = FakeLLM(deltas=["Смотри:", Figure(figure), "Синус.", Sources((Source("https://a.example/", "A"),))])
    token = await token_for(client)
    done = parse_sse((await client.post("/v1/chat", json=chat_body(), headers=bearer(token))).text)[-1][1]
    assert done["text"] == "Смотри:\n\n" + fenced_block(figure) + "\n\nСинус."
    assert done["message"]["content"] == done["text"]
    assert done["message"]["sources"] == [{"url": "https://a.example/", "title": "A"}] == done["sources"]


async def test_chat_validation(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    cases = [
        chat_body(""),  # empty without attachments
        chat_body("   "),
        chat_body("x", answer_id="m1"),  # answer id = message id
        chat_body("x", dialog_id="bad id"),
        {**chat_body("x"), "answer_id": None},
        {"dialog_id": "d1", "answer_id": "a1"},  # no message
        chat_body("x", effort="extreme"),
        chat_body("x", attachments=["a_1", "a_1"]),
        chat_body("x", attachments=["a_1", "a_2", "a_3", "a_4", "a_5"]),
        chat_body("x", attachments=["a_unknown"]),
    ]
    for body in cases:
        response = await client.post("/v1/chat", json=body, headers=bearer(token))
        assert response.status_code == 400, body
        assert response.json()["error"] == "invalid_request"
    denied = await client.post("/v1/chat", json=chat_body("x", model="evil/model"), headers=bearer(token))
    assert denied.status_code == 400 and denied.json()["error"] == "model_not_allowed"
    assert app.state.llm.requests == []
    assert (await sync(client, token))["messages"] == []  # nothing saved for a refused request


async def test_ids_of_another_user_are_a_conflict(client: AsyncClient) -> None:
    mine = await token_for(client)
    theirs = await token_for(client, device_id="phone-2", name="Аня")
    await client.post("/v1/chat", json=chat_body(), headers=bearer(mine))
    for body in (chat_body(), chat_body(dialog_id="d9"), chat_body(dialog_id="d9", message_id="m9")):
        response = await client.post("/v1/chat", json=body, headers=bearer(theirs))
        assert response.status_code == 409, body
    assert (await sync(client, theirs))["messages"] == []


async def test_a_repeated_request_shows_the_same_answer(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    first = parse_sse((await client.post("/v1/chat", json=chat_body(), headers=bearer(token))).text)[-1][1]
    again = parse_sse((await client.post("/v1/chat", json=chat_body(), headers=bearer(token))).text)
    assert [name for name, _ in again] == ["done"]
    assert again[0][1]["message"] == first["message"] and again[0][1]["text"] == "Hello world"
    assert len(app.state.llm.requests) == 1  # not asked twice
    assert len((await sync(client, token))["messages"]) == 2


async def test_a_retry_while_streaming_joins_the_running_answer(app: FastAPI, client: AsyncClient) -> None:
    gate = asyncio.Event()
    app.state.llm = FakeLLM(deltas=["Hel", "lo"], gate=gate)
    token = await token_for(client)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as other:
        first = asyncio.create_task(client.post("/v1/chat", json=chat_body(), headers=bearer(token)))
        await asyncio.wait_for(app.state.llm.started.wait(), 5)
        second = asyncio.create_task(other.post("/v1/chat", json=chat_body(), headers=bearer(token)))
        await asyncio.sleep(0.05)
        gate.set()
        a, b = await asyncio.gather(first, second)
    assert parse_sse(a.text) == parse_sse(b.text)  # the late listener caught up from the start
    assert [name for name, _ in parse_sse(b.text)] == ["delta", "delta", "done"]
    assert len(app.state.llm.requests) == 1


async def test_the_answer_continues_after_the_client_disconnects(app: FastAPI, client: AsyncClient) -> None:
    gate = asyncio.Event()
    app.state.llm = FakeLLM(deltas=["Hel", "lo", " world"], gate=gate)
    token = await token_for(client)
    async with AsyncStreamChat(app, token) as stream:
        assert await stream.next_event() == ("delta", {"text": "Hel"})
    assert stream.task is not None and stream.task.done()  # the response is gone
    assert app.state.answers.get("a1") is not None
    gate.set()
    await wait_until_finished(app, "a1")
    answer = (await sync(client, token))["messages"][-1]
    assert (answer["status"], answer["content"]) == ("done", "Hello world")


def AsyncStreamChat(app: FastAPI, token: str, **body: object) -> AsgiStream:
    return AsgiStream(app, "POST", "/v1/chat", headers=bearer(token), json_body=chat_body(**body))


async def test_stop_ends_the_answer_with_what_arrived(app: FastAPI, client: AsyncClient) -> None:
    gate = asyncio.Event()
    app.state.llm = FakeLLM(deltas=[Thinking("hmm"), "Hel", "lo"], gate=gate, gate_after=2)
    token = await token_for(client)
    async with AsyncStreamChat(app, token) as stream:
        await stream.until("delta")
        stopped = await client.post("/v1/messages/a1/stop", headers=bearer(token))
        assert stopped.status_code == 204
        events = await stream.until("done")
    done = events[-1][1]
    assert done["text"] == "Hel" and done["message"]["status"] == "done" and done["message"]["content"] == "Hel"
    assert done["thinking"] == "hmm"
    answer = (await sync(client, token))["messages"][-1]
    assert (answer["status"], answer["content"], answer["error"]) == ("done", "Hel", "")
    # Stopping a finished answer changes nothing; unknown ids and other users' answers are 404.
    assert (await client.post("/v1/messages/a1/stop", headers=bearer(token))).status_code == 204
    assert (await client.post("/v1/messages/zzz/stop", headers=bearer(token))).status_code == 404
    other = await token_for(client, device_id="phone-2", name="Аня")
    assert (await client.post("/v1/messages/a1/stop", headers=bearer(other))).status_code == 404


async def test_a_streaming_answer_is_saved_and_announced_while_it_grows(app: FastAPI, client: AsyncClient) -> None:
    gate = asyncio.Event()
    app.state.llm = FakeLLM(deltas=["Hel", "lo"], gate=gate)
    phone = await register(client)
    async with AsgiStream(app, "GET", "/v1/events", headers=bearer(phone["token"])) as events:
        assert (await events.next_event())[0] == "changed"
        async with AsyncStreamChat(app, phone["token"]) as stream:
            await stream.next_event()
            # The question and the streaming answer were saved and announced...
            changed = await events.next_event()
            assert changed[0] == "changed"
            # ...and the growing text is saved within save_interval.
            for _ in range(100):
                answer = (await sync(client, phone["token"]))["messages"][-1]
                if answer["content"] == "Hel":
                    break
                await asyncio.sleep(0.02)
            assert (answer["status"], answer["content"]) == ("streaming", "Hel")
            gate.set()
            done = (await stream.until("done"))[-1][1]
        final = await events.next_event()
        while final[1]["seq"] < done["seq"]:
            final = await events.next_event()
        assert final == ("changed", {"seq": done["seq"]})


async def test_deleting_a_streaming_answer_stops_it(app: FastAPI, client: AsyncClient) -> None:
    gate = asyncio.Event()
    app.state.llm = FakeLLM(deltas=["Hel", "lo"], gate=gate)
    token = await token_for(client)
    async with AsyncStreamChat(app, token) as stream:
        await stream.next_event()
        assert (await client.delete("/v1/dialogs/d1", headers=bearer(token))).status_code == 204
        done = (await stream.until("done"))[-1][1]
    assert done["message"]["deleted"] is True and done["message"]["content"] == ""
    answer = (await sync(client, token))["messages"][-1]
    assert answer["deleted"] is True and answer["content"] == ""


async def test_shutdown_saves_running_answers_as_errors(tmp_path: Path) -> None:
    app = create_app(Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k"))
    gate = asyncio.Event()
    app.state.llm = FakeLLM(deltas=["Hel", "lo"], gate=gate)
    async with app.router.lifespan_context(app):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            token = await token_for(client)
            async with AsyncStreamChat(app, token) as stream:
                await stream.next_event()
                await app.state.answers.shutdown()
                assert (await stream.until("error"))[-1][1]["error"] == "internal_error"
            answer = (await sync(client, token))["messages"][-1]
            assert (answer["status"], answer["error"], answer["content"]) == ("error", "internal_error", "Hel")


async def test_answers_left_streaming_by_a_crash_become_errors_on_start(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k")
    app = create_app(settings)
    app.state.llm = FakeLLM(deltas=["Hel", "lo"], gate=asyncio.Event())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await token_for(client)
        async with AsyncStreamChat(app, token) as stream:
            await stream.next_event()
        app.state.answers.get("a1").task.cancel()  # the process dies: nothing more is saved
    app.state.store.close()
    restarted = create_app(settings)
    restarted.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=restarted), base_url="http://test") as client:
        answer = (await sync(client, token))["messages"][-1]
        assert (answer["status"], answer["error"]) == ("error", "internal_error")
        # A retry of that request reports how it ended.
        retry = parse_sse((await client.post("/v1/chat", json=chat_body(), headers=bearer(token))).text)
        assert retry == [("error", {"error": "internal_error", "message": "the answer failed"})]


async def test_rate_limits_are_per_user_across_devices(tmp_path: Path) -> None:
    app = create_app(Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k", chat_per_min=2))
    app.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        phone = await register(client)
        link = (await client.post("/v1/link/start", json={"device_id": "watch-1", "device_kind": "watch"})).json()
        await client.post("/v1/link/claim", json={"code": link["code"]}, headers=bearer(phone["token"]))
        watch = (await client.get("/v1/link/status", params={"link_id": link["link_id"]})).json()["token"]
        assert (await client.post("/v1/chat", json=chat_body(), headers=bearer(phone["token"]))).status_code == 200
        legacy = {"messages": [{"role": "user", "content": "hi"}]}
        assert (await client.post("/v1/chat", json=legacy, headers=bearer(watch))).status_code == 200
        limited = await client.post("/v1/chat", json=chat_body(message_id="m2", answer_id="a2"), headers=bearer(phone["token"]))
        assert limited.status_code == 429 and limited.json()["error"] == "rate_limited"
        assert "retry-after" in limited.headers
        other = await token_for(client, device_id="phone-other", name="Other")
        assert (await client.post("/v1/chat", json=chat_body(dialog_id="x", message_id="x1", answer_id="x2"),
                                  headers=bearer(other))).status_code == 200


async def test_concurrent_chats_get_unique_sequence_numbers(client: AsyncClient) -> None:
    token = await token_for(client)
    responses = await asyncio.gather(*(
        client.post("/v1/chat", json=chat_body(f"q{i}", dialog_id=f"d{i % 3}", message_id=f"m{i}", answer_id=f"a{i}"),
                    headers=bearer(token))
        for i in range(12)
    ))
    assert all(r.status_code == 200 for r in responses)
    synced = (await client.get("/v1/sync", params={"limit": 1000}, headers=bearer(token))).json()
    seqs = [o["seq"] for o in synced["dialogs"] + synced["messages"]]
    assert len(seqs) == len(set(seqs)) == 3 + 24
    assert synced["seq"] == max(seqs)
