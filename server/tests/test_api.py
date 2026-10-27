import json
from pathlib import Path
from typing import AsyncIterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.llm import LLMError, Thinking, Usage


class FakeLLM:
    def __init__(self, deltas: list[str | Thinking] | None = None, error: LLMError | None = None) -> None:
        self.deltas = deltas or [Thinking("Greeting "), Thinking("the user."), "Hel", "lo", " world"]
        self.error = error
        self.requests: list[tuple[list[dict[str, str]], str]] = []

    async def stream(self, messages: list[dict[str, str]], model: str) -> AsyncIterator[str | Thinking | Usage]:
        self.requests.append((messages, model))
        if self.error:
            raise self.error
        for delta in self.deltas:
            yield delta
        yield Usage(prompt_tokens=10, completion_tokens=3)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="test-key", openrouter_api_key="test-key")


@pytest.fixture
def app(settings: Settings) -> FastAPI:
    application = create_app(settings=settings)
    application.state.llm = FakeLLM()
    return application


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac


PAIR = {"passcode": "1234", "device_id": "dev-1", "device_name": "Galaxy Watch7"}


async def pair(client: AsyncClient, **overrides: str) -> str:
    response = await client.post("/v1/pair", json={**PAIR, **overrides})
    assert response.status_code in (200, 201), response.text
    return response.json()["token"]


def parse_sse(body: str) -> list[tuple[str, dict]]:
    events = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        events.append((lines["event"], json.loads(lines["data"])))
    return events


# ---- health and pairing -----------------------------------------------------


async def test_healthz_reports_pairing(client: AsyncClient) -> None:
    assert (await client.get("/healthz")).json() == {"ok": True, "paired": False}
    await pair(client)
    assert (await client.get("/healthz")).json() == {"ok": True, "paired": True}


async def test_pair_flow_201_200_401(client: AsyncClient) -> None:
    first = await client.post("/v1/pair", json=PAIR)
    assert first.status_code == 201
    body = first.json()
    assert body["first_setup"] is True
    assert body["server_name"] == "FullSkat"
    assert body["model"] == "claude-haiku-5-5"
    assert body["models"][0] == "claude-haiku-5-5"
    assert len(body["token"]) >= 43

    second = await client.post("/v1/pair", json={**PAIR, "device_id": "dev-2"})
    assert second.status_code == 200
    assert second.json()["first_setup"] is False

    wrong = await client.post("/v1/pair", json={**PAIR, "passcode": "9999"})
    assert wrong.status_code == 401
    assert wrong.json()["error"] == "invalid_passcode"


async def test_pair_validates_passcode_format(client: AsyncClient) -> None:
    for bad in ("123", "123456789", "12ab", "١٢٣٤"):
        response = await client.post("/v1/pair", json={**PAIR, "passcode": bad})
        assert response.status_code == 400, bad
        assert response.json()["error"] == "invalid_passcode_format"
    response = await client.post("/v1/pair", json={"passcode": "1234"})
    assert response.status_code == 400
    assert response.json()["error"] == "invalid_request"


async def test_pair_rate_limited_on_sixth_attempt(client: AsyncClient) -> None:
    for _ in range(5):
        response = await client.post("/v1/pair", json=PAIR)
        assert response.status_code in (200, 201)
    sixth = await client.post("/v1/pair", json=PAIR)
    assert sixth.status_code == 429
    assert sixth.json()["error"] == "rate_limited"
    assert int(sixth.headers["retry-after"]) >= 1


async def test_pair_lockout_after_consecutive_failures(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, data_dir=tmp_path, pair_per_min=100, pair_lock_failures=3)
    app = create_app(settings=settings)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        await pair(client)
        for _ in range(3):
            assert (await client.post("/v1/pair", json={**PAIR, "passcode": "0000"})).status_code == 401
        locked = await client.post("/v1/pair", json=PAIR)
        assert locked.status_code == 429
        assert locked.json()["error"] == "locked"
        assert int(locked.headers["retry-after"]) > 60


async def test_repair_revokes_old_token(client: AsyncClient) -> None:
    old = await pair(client)
    new = await pair(client)
    assert (await client.get("/v1/me", headers={"Authorization": f"Bearer {old}"})).status_code == 401
    assert (await client.get("/v1/me", headers={"Authorization": f"Bearer {new}"})).status_code == 200


# ---- /v1/me ---------------------------------------------------------------


async def test_me(client: AsyncClient) -> None:
    token = await pair(client)
    response = await client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert response.json() == {
        "device_id": "dev-1",
        "device_name": "Galaxy Watch7",
        "model": "claude-haiku-5-5",
        "models": ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"],
        "limits": {"chat_per_min": 20, "chat_per_day": 500},
    }


async def test_me_rejects_missing_and_bad_tokens(client: AsyncClient) -> None:
    assert (await client.get("/v1/me")).json() == {
        "error": "missing_token",
        "message": "Authorization: Bearer <token> is required",
    }
    response = await client.get("/v1/me", headers={"Authorization": "Bearer nope"})
    assert response.status_code == 401
    assert response.json()["error"] == "invalid_token"


# ---- /v1/chat -------------------------------------------------------------


async def test_chat_streams_deltas_and_done(client: AsyncClient, app: FastAPI) -> None:
    token = await pair(client)
    request = {
        "dialog_id": "d1",
        "locale": "ru-RU",
        "messages": [{"role": "user", "content": "Привет"}],
    }
    response = await client.post(
        "/v1/chat", json=request, headers={"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}
    )
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
    }

    (messages, model), = app.state.llm.requests
    assert model == "claude-haiku-5-5"
    assert messages[0]["role"] == "system"
    assert "LaTeX" in messages[0]["content"]
    assert "User locale: ru-RU" in messages[0]["content"]
    assert messages[1:] == [{"role": "user", "content": "Привет"}]


async def test_chat_done_has_empty_thinking_without_summary(client: AsyncClient, app: FastAPI) -> None:
    app.state.llm = FakeLLM(deltas=["Hi"])
    token = await pair(client)
    body = {"messages": [{"role": "user", "content": "hi"}]}
    response = await client.post("/v1/chat", json=body, headers={"Authorization": f"Bearer {token}"})
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["delta", "done"]
    assert events[-1][1]["thinking"] == ""


async def test_chat_model_allowlist_and_errors(client: AsyncClient, app: FastAPI) -> None:
    token = await pair(client)
    headers = {"Authorization": f"Bearer {token}"}
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


async def test_chat_body_size_limit(client: AsyncClient) -> None:
    token = await pair(client)
    huge = {"messages": [{"role": "user", "content": "x" * (64 * 1024)}]}
    response = await client.post("/v1/chat", json=huge, headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 413
    assert response.json()["error"] == "payload_too_large"


async def test_chat_history_is_trimmed(client: AsyncClient, app: FastAPI) -> None:
    token = await pair(client)
    history = [{"role": "user" if i % 2 == 0 else "assistant", "content": f"m{i}"} for i in range(40)]
    response = await client.post("/v1/chat", json={"messages": history}, headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    sent = app.state.llm.requests[-1][0]
    assert len(sent) == 25  # system prompt + last 24
    assert sent[-1]["content"] == "m39"


async def test_chat_rate_limit(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, data_dir=tmp_path, chat_per_min=2)
    app = create_app(settings=settings)
    app.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await pair(client)
        headers = {"Authorization": f"Bearer {token}"}
        body = {"messages": [{"role": "user", "content": "hi"}]}
        for _ in range(2):
            assert (await client.post("/v1/chat", json=body, headers=headers)).status_code == 200
        limited = await client.post("/v1/chat", json=body, headers=headers)
        assert limited.status_code == 429
        assert limited.json()["error"] == "rate_limited"
        assert "retry-after" in limited.headers
