"""Test doubles and helpers shared by the API tests."""

from __future__ import annotations

import asyncio
import io
import json
import time
from typing import Any, AsyncIterator
from urllib.parse import urlsplit

from httpx import AsyncClient

from fullskat_server.efforts import ModelEfforts
from fullskat_server.llm import Figure, LLMError, Thinking, Usage

MODELS = ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"]


class FakeLLM:
    """Stands in for llm.MultiLLM: scripted stream items, records every request.

    `gate`: when set, the stream yields `deltas[:gate_after]`, waits for the event, then yields the rest.
    `error`: raised after `error_after` deltas (before the first one by default).
    """

    DEFAULT_EFFORTS = ModelEfforts(("low", "medium", "high", "xhigh", "max"), "medium")

    def __init__(
        self,
        deltas: list[Any] | None = None,
        error: LLMError | None = None,
        efforts: dict[str, ModelEfforts] | None = None,
        model_list: list[str] | None = None,
        gate: asyncio.Event | None = None,
        gate_after: int = 1,
        error_after: int = 0,
    ) -> None:
        self.efforts = efforts or {}
        self.model_list = model_list if model_list is not None else list(MODELS)
        self.deltas = deltas if deltas is not None else [Thinking("Greeting "), Thinking("the user."), "Hel", "lo", " world"]
        self.error = error
        self.error_after = error_after
        self.gate = gate
        self.gate_after = gate_after
        self.requests: list[tuple[list[dict[str, Any]], str]] = []
        self.options: list[dict[str, object]] = []
        self.figure_flags: list[bool | None] = []
        self.started = asyncio.Event()

    async def models(self) -> tuple[str, list[str]]:
        return (self.model_list[0] if self.model_list else ""), self.model_list

    async def providers_info(self) -> dict[str, dict[str, object]]:
        return {"anthropic": {"ok": True, "reason": "", "models": self.model_list, "usage": None}}

    async def model_efforts(self, models: list[str]) -> dict[str, ModelEfforts]:
        return {m: self.efforts.get(m, self.DEFAULT_EFFORTS) for m in models}

    async def stream(
        self,
        messages: list[dict[str, Any]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
        figures: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Figure | Usage]:
        self.requests.append((messages, model))
        self.options.append({"effort": effort, "web_search": web_search})
        self.figure_flags.append(figures)
        self.started.set()
        for index, delta in enumerate(self.deltas):
            if self.error is not None and index == self.error_after:
                raise self.error
            if self.gate is not None and index == self.gate_after:
                await self.gate.wait()
            yield delta
        if self.error:
            raise self.error
        yield Usage(prompt_tokens=10, completion_tokens=3)


class FakeClock:
    """Unix seconds that start where a test puts them and then run on with real time."""

    def __init__(self, start: float = 1_800_000_000.0) -> None:
        self.set(start)

    def set(self, t: float) -> None:
        self._base = t
        self._at = time.monotonic()

    def advance(self, seconds: float) -> None:
        self.set(self() + seconds)

    def __call__(self) -> float:
        return self._base + (time.monotonic() - self._at)


REGISTER = {"passcode": "1234", "name": "Миша", "device_id": "phone-1", "device_name": "Pixel 9", "device_kind": "phone"}


async def register(client: AsyncClient, **overrides: Any) -> dict[str, Any]:
    response = await client.post("/v1/register", json={**REGISTER, **overrides})
    assert response.status_code in (200, 201), response.text
    return response.json()


async def token_for(client: AsyncClient, **overrides: Any) -> str:
    return (await register(client, **overrides))["token"]


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def parse_sse(body: str) -> list[tuple[str, dict]]:
    events = []
    for block in body.strip().split("\n\n"):
        if not block.strip():
            continue
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        events.append((lines["event"], json.loads(lines["data"])))
    return events


def chat_body(text: str = "Привет", dialog_id: str = "d1", message_id: str = "m1", answer_id: str = "a1",
              **extra: Any) -> dict[str, Any]:
    attachments = extra.pop("attachments", [])
    return {
        "dialog_id": dialog_id,
        "message": {"id": message_id, "content": text, "attachments": attachments},
        "answer_id": answer_id,
        **extra,
    }


def jpeg_bytes(width: int = 64, height: int = 48, color: tuple[int, int, int] = (200, 30, 30), **save: Any) -> bytes:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buffer, "JPEG", **save)
    return buffer.getvalue()


class AsgiStream:
    """Drives one request through the ASGI app by hand, so a streaming response can be read while it streams
    and the client can disconnect midway (httpx's ASGITransport buffers the whole body)."""

    def __init__(self, app: Any, method: str, url: str, headers: dict[str, str] | None = None,
                 json_body: Any = None) -> None:
        parts = urlsplit(url)
        body = json.dumps(json_body).encode() if json_body is not None else b""
        raw_headers = [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()]
        if json_body is not None:
            raw_headers += [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]
        self._scope = {
            "type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"}, "http_version": "1.1",
            "method": method, "scheme": "http", "path": parts.path, "raw_path": parts.path.encode(),
            "query_string": parts.query.encode(), "headers": raw_headers, "server": ("test", 80),
            "client": ("127.0.0.1", 50000), "root_path": "",
        }
        self._app = app
        self._body = body
        self._body_sent = False
        self._disconnect = asyncio.Event()
        self._started = asyncio.Event()
        self._chunks: asyncio.Queue[bytes | None] = asyncio.Queue()
        self._buffer = ""
        self.status: int | None = None
        self.ended = False
        self.task: asyncio.Task[None] | None = None

    async def __aenter__(self) -> AsgiStream:
        async def receive() -> dict[str, Any]:
            if not self._body_sent:
                self._body_sent = True
                return {"type": "http.request", "body": self._body, "more_body": False}
            await self._disconnect.wait()
            return {"type": "http.disconnect"}

        async def send(message: dict[str, Any]) -> None:
            if message["type"] == "http.response.start":
                self.status = message["status"]
                self._started.set()
            elif message["type"] == "http.response.body":
                if message.get("body"):
                    await self._chunks.put(message["body"])
                if not message.get("more_body", False):
                    await self._chunks.put(None)

        self.task = asyncio.create_task(self._app(self._scope, receive, send))
        await asyncio.wait_for(self._started.wait(), 5)
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.disconnect()

    async def next_event(self, timeout: float = 5.0) -> tuple[str, dict] | None:
        """The next SSE event, or None when the response ended."""
        while "\n\n" not in self._buffer:
            if self.ended:
                return None
            chunk = await asyncio.wait_for(self._chunks.get(), timeout)
            if chunk is None:
                self.ended = True
                continue
            self._buffer += chunk.decode()
        block, self._buffer = self._buffer.split("\n\n", 1)
        return parse_sse(block + "\n\n")[0]

    async def until(self, name: str, timeout: float = 5.0) -> list[tuple[str, dict]]:
        """Events up to and including the first one called `name`."""
        seen = []
        while True:
            event = await self.next_event(timeout)
            assert event is not None, f"stream ended before {name!r}: {seen}"
            seen.append(event)
            if event[0] == name:
                return seen

    async def disconnect(self) -> None:
        self._disconnect.set()
        if self.task is not None:
            await asyncio.wait_for(self.task, 5)
