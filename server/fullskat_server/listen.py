"""WebSocket listening session: one utterance per connection (see docs/PROTOCOL.md)."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from fastapi import WebSocket
from starlette.websockets import WebSocketDisconnect, WebSocketState

from .config import Settings
from .llm import LLMError
from .stt.base import STTBackend, Transcript
from .vad import EnergyVAD

log = logging.getLogger(__name__)

START_TIMEOUT_S = 10.0  # waiting for the start frame
IDLE_TIMEOUT_S = 15.0  # no frames at all after start: treat as end of utterance
MIN_FINAL_MS = 300  # no partial or final transcription below this much audio
ALLOWED_LANGS = {"auto", "ru", "en"}
CLOSE_POLICY_VIOLATION = 1008
CLOSE_INTERNAL_ERROR = 1011


class StartFrameError(ValueError):
    """Malformed or unsupported start frame."""


def parse_start_frame(raw: str) -> tuple[int, str | None]:
    """Validate the start frame; return (sample_rate, lang-or-None-for-auto)."""
    try:
        frame = json.loads(raw)
    except ValueError as exc:
        raise StartFrameError("first frame must be JSON") from exc
    if not isinstance(frame, dict) or frame.get("type") != "start":
        raise StartFrameError('first frame must be {"type": "start", ...}')
    sample_rate = frame.get("sample_rate", 16000)
    if not isinstance(sample_rate, int) or not 8000 <= sample_rate <= 48000:
        raise StartFrameError("sample_rate must be an integer between 8000 and 48000")
    if frame.get("encoding", "pcm_s16le") != "pcm_s16le":
        raise StartFrameError("encoding must be pcm_s16le")
    if frame.get("channels", 1) != 1:
        raise StartFrameError("channels must be 1")
    lang = frame.get("lang", "auto")
    if lang not in ALLOWED_LANGS:
        raise StartFrameError("lang must be auto, ru or en")
    return sample_rate, None if lang == "auto" else lang


class ListenSession:
    """Drives one utterance: buffers audio, runs VAD, streams partials, emits the final transcript."""

    def __init__(self, ws: WebSocket, settings: Settings, stt: STTBackend) -> None:
        self._ws = ws
        self._settings = settings
        self._stt = stt
        self._send_lock = asyncio.Lock()
        self._buffer = bytearray()
        self._sample_rate = 16000
        self._lang: str | None = None
        self._vad = EnergyVAD()
        self._partial_task: asyncio.Task[None] | None = None
        self._partial_text = ""
        self._last_partial_at = 0.0
        self._last_partial_bytes = 0
        self._closed = False

    # ---- helpers -----------------------------------------------------------

    @property
    def _max_bytes(self) -> int:
        return self._settings.max_utterance_s * self._sample_rate * 2

    @property
    def _audio_ms(self) -> int:
        return len(self._buffer) * 1000 // (self._sample_rate * 2)

    async def _send(self, payload: dict[str, Any]) -> None:
        if self._closed:
            return
        async with self._send_lock:
            if self._ws.client_state == WebSocketState.CONNECTED:
                await self._ws.send_text(json.dumps(payload, ensure_ascii=False))

    async def _close(self, code: int) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            await self._ws.close(code=code)
        except RuntimeError:
            pass  # already closed by the peer

    async def _fail(self, code: str, message: str, close_code: int = CLOSE_INTERNAL_ERROR) -> None:
        await self._send({"type": "error", "error": code, "message": message})
        await self._close(close_code)

    # ---- partial transcription --------------------------------------------

    async def _run_partial(self, audio: bytes) -> None:
        try:
            transcript = await self._stt.transcribe(audio, self._sample_rate, self._lang, self._partial_text or None)
        except LLMError as exc:
            log.warning("Partial transcription failed: %s", exc.message)
            return
        except Exception:
            log.exception("Partial transcription crashed")
            return
        if transcript.text and transcript.text != self._partial_text:
            self._partial_text = transcript.text
            await self._send({"type": "partial", "text": transcript.text})

    def _maybe_schedule_partial(self) -> None:
        if not self._vad.speech_detected or self._audio_ms < MIN_FINAL_MS:
            return
        if self._partial_task is not None and not self._partial_task.done():
            return
        now = time.monotonic()
        if now - self._last_partial_at < self._settings.partial_interval_ms / 1000:
            return
        if len(self._buffer) <= self._last_partial_bytes:
            return
        self._last_partial_at = now
        self._last_partial_bytes = len(self._buffer)
        self._partial_task = asyncio.create_task(self._run_partial(bytes(self._buffer)))

    async def _cancel_partial(self) -> None:
        task = self._partial_task
        self._partial_task = None
        if task is None or task.done():
            return
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass

    # ---- audio intake ------------------------------------------------------

    async def _ingest(self, chunk: bytes) -> bool:
        """Append audio; return True when the utterance should end (silence or cap)."""
        room = self._max_bytes - len(self._buffer)
        if room <= 0:
            return True
        chunk = chunk[:room]
        self._buffer.extend(chunk)
        for active in self._vad.feed(chunk):
            await self._send({"type": "speech", "active": active})
        if len(self._buffer) >= self._max_bytes:
            log.info("Utterance reached the %ss cap", self._settings.max_utterance_s)
            return True
        silence = self._vad.silence_ms_since_speech
        if silence is not None and not self._vad.speech_active and silence >= self._settings.silence_ms:
            return True
        if not self._vad.speech_detected and self._audio_ms >= self._settings.no_speech_timeout_s * 1000:
            log.info("No speech within %ss; finishing", self._settings.no_speech_timeout_s)
            return True
        self._maybe_schedule_partial()
        return False

    # ---- finalization ------------------------------------------------------

    async def _final_transcript(self) -> Transcript:
        if self._audio_ms < MIN_FINAL_MS:
            return Transcript(text="", lang=self._lang)
        return await self._stt.transcribe(
            bytes(self._buffer), self._sample_rate, self._lang, self._partial_text or None, final=True
        )

    async def _finish(self) -> None:
        await self._cancel_partial()
        try:
            transcript = await self._final_transcript()
        except LLMError as exc:
            await self._fail(exc.code, exc.message)
            return
        except Exception:
            log.exception("Final transcription crashed")
            await self._fail("internal_error", "transcription failed")
            return
        await self._send({"type": "final", "text": transcript.text, "lang": transcript.lang})
        await self._close(1000)

    # ---- main loop ---------------------------------------------------------

    async def run(self) -> None:
        """Serve the session on an already-accepted socket."""
        try:
            await self._run()
        except WebSocketDisconnect:
            log.info("Listen client disconnected (%d ms buffered)", self._audio_ms)
            self._closed = True
            await self._cancel_partial()
        except Exception:
            log.exception("Listen session crashed")
            await self._cancel_partial()
            await self._fail("internal_error", "listen session failed")

    async def _receive(self, timeout: float) -> dict[str, Any]:
        try:
            message = await asyncio.wait_for(self._ws.receive(), timeout=timeout)
        except asyncio.TimeoutError:
            return {"type": "timeout"}
        if message.get("type") == "websocket.disconnect":
            raise WebSocketDisconnect(message.get("code", 1005))
        return message

    async def _run(self) -> None:
        first = await self._receive(START_TIMEOUT_S)
        if first["type"] == "timeout" or first.get("text") is None:
            await self._fail("bad_start", "expected a JSON start frame first", CLOSE_POLICY_VIOLATION)
            return
        try:
            self._sample_rate, self._lang = parse_start_frame(first["text"])
        except StartFrameError as exc:
            await self._fail("bad_start", str(exc), CLOSE_POLICY_VIOLATION)
            return
        self._vad = EnergyVAD(sample_rate=self._sample_rate)
        await self._send({"type": "ready"})

        while True:
            message = await self._receive(IDLE_TIMEOUT_S)
            if message["type"] == "timeout":
                log.info("Listen session idle for %.0fs; finishing", IDLE_TIMEOUT_S)
                break
            data = message.get("bytes")
            if data is not None:
                if await self._ingest(data):
                    break
                continue
            text = message.get("text")
            if text is None:
                continue
            try:
                frame = json.loads(text)
            except ValueError:
                await self._fail("bad_frame", "text frames must be JSON", CLOSE_POLICY_VIOLATION)
                return
            if isinstance(frame, dict) and frame.get("type") == "stop":
                break
            await self._fail("bad_frame", "unexpected text frame", CLOSE_POLICY_VIOLATION)
            return
        await self._finish()
