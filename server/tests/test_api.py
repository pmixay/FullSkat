import json
from pathlib import Path
from typing import AsyncIterator

import numpy as np
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.llm import LLMError, Usage
from fullskat_server.stt.base import Transcript


class FakeSTT:
    def __init__(self, text: str = "привет мир") -> None:
        self.text = text
        self.calls: list[tuple[int, bool]] = []

    async def transcribe(
        self, pcm16: bytes, sample_rate: int, lang: str | None, prior_text: str | None, *, final: bool = False
    ) -> Transcript:
        self.calls.append((len(pcm16), final))
        return Transcript(text=self.text, lang="ru")


class FakeLLM:
    def __init__(self, deltas: list[str] | None = None, error: LLMError | None = None) -> None:
        self.deltas = deltas or ["Hel", "lo", " world"]
        self.error = error
        self.requests: list[tuple[list[dict[str, str]], str]] = []

    async def stream(self, messages: list[dict[str, str]], model: str) -> AsyncIterator[str | Usage]:
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
    application = create_app(settings=settings, stt=FakeSTT())
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
    app = create_app(settings=settings, stt=FakeSTT())
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
        "limits": {"chat_per_min": 20, "listen_per_min": 40, "chat_per_day": 500, "listen_per_day": 1000},
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
    assert [name for name, _ in events] == ["delta", "delta", "delta", "done"]
    assert events[0][1] == {"text": "Hel"}
    assert events[-1][1] == {
        "text": "Hello world",
        "model": "claude-haiku-5-5",
        "usage": {"prompt_tokens": 10, "completion_tokens": 3},
    }

    (messages, model), = app.state.llm.requests
    assert model == "claude-haiku-5-5"
    assert messages[0]["role"] == "system"
    assert "LaTeX" in messages[0]["content"]
    assert "User locale: ru-RU" in messages[0]["content"]
    assert messages[1:] == [{"role": "user", "content": "Привет"}]


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
    app = create_app(settings=settings, stt=FakeSTT())
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


# ---- /v1/listen -----------------------------------------------------------


def loud_frame(ms: int = 100, sample_rate: int = 16000) -> bytes:
    t = np.arange(sample_rate * ms // 1000) / sample_rate
    return (np.sin(2 * np.pi * 440 * t) * 9000).astype(np.int16).tobytes()


def drain(ws) -> tuple[list[dict], int | None]:
    """Read text frames until the server closes; return (frames, close code)."""
    frames: list[dict] = []
    while True:
        message = ws.receive()
        if message["type"] == "websocket.close":
            return frames, message.get("code")
        if message.get("text") is not None:
            frames.append(json.loads(message["text"]))


def test_listen_flow_with_fake_stt(app: FastAPI) -> None:
    with TestClient(app) as tc:
        token = tc.post("/v1/pair", json=PAIR).json()["token"]
        with tc.websocket_connect(f"/v1/listen?token={token}") as ws:
            ws.send_text(json.dumps({"type": "start", "sample_rate": 16000, "encoding": "pcm_s16le", "channels": 1, "lang": "auto"}))
            assert ws.receive_json() == {"type": "ready"}
            for _ in range(5):
                ws.send_bytes(loud_frame())
            ws.send_text(json.dumps({"type": "stop"}))
            frames, code = drain(ws)

    kinds = [frame["type"] for frame in frames]
    assert kinds.count("final") == 1
    assert {"type": "speech", "active": True} in frames
    final = next(frame for frame in frames if frame["type"] == "final")
    assert final == {"type": "final", "text": "привет мир", "lang": "ru"}
    assert kinds[-1] == "final"
    assert code == 1000
    stt: FakeSTT = app.state.stt
    assert stt.calls[-1] == (5 * 3200, True)


def test_listen_header_auth_and_short_audio(app: FastAPI) -> None:
    with TestClient(app) as tc:
        token = tc.post("/v1/pair", json=PAIR).json()["token"]
        with tc.websocket_connect("/v1/listen", headers={"Authorization": f"Bearer {token}"}) as ws:
            ws.send_text(json.dumps({"type": "start", "sample_rate": 16000}))
            assert ws.receive_json() == {"type": "ready"}
            ws.send_bytes(loud_frame(100))
            ws.send_text(json.dumps({"type": "stop"}))
            frames, code = drain(ws)
    assert frames[-1] == {"type": "final", "text": "", "lang": None}
    assert code == 1000
    assert app.state.stt.calls == []


def test_listen_rejects_bad_token_and_bad_start(app: FastAPI) -> None:
    with TestClient(app) as tc:
        with tc.websocket_connect("/v1/listen?token=bogus") as ws:
            frames, code = drain(ws)
        assert frames == [{"type": "error", "error": "invalid_token", "message": "token is unknown or was revoked"}]
        assert code == 1008

        token = tc.post("/v1/pair", json=PAIR).json()["token"]
        with tc.websocket_connect(f"/v1/listen?token={token}") as ws:
            ws.send_text(json.dumps({"type": "start", "encoding": "mp3"}))
            frames, code = drain(ws)
        assert frames[0]["type"] == "error"
        assert frames[0]["error"] == "bad_start"
        assert code == 1008


def test_listen_rate_limited(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, data_dir=tmp_path, listen_per_min=1)
    app = create_app(settings=settings, stt=FakeSTT())
    with TestClient(app) as tc:
        token = tc.post("/v1/pair", json=PAIR).json()["token"]
        with tc.websocket_connect(f"/v1/listen?token={token}") as ws:
            ws.send_text(json.dumps({"type": "stop"}))
            drain(ws)
        with tc.websocket_connect(f"/v1/listen?token={token}") as ws:
            frames, code = drain(ws)
    assert frames[0]["error"] == "rate_limited"
    assert code == 1008
