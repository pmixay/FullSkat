"""Answers generated on the server, independent of the HTTP connection that asked for them.

`Answer` streams from the model in its own task, keeps the assistant message in the database up to date
(status `streaming`, saved at most every SAVE_INTERVAL seconds, then `done` or `error`) and fans its events
out to any number of listeners: the chat response that started it, a retry of the same request. When the
client goes away the task simply goes on. `AnswerBuilder` turns model stream items into SSE events and the
final text; the legacy stateless chat uses it too.
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import aclosing, suppress
from typing import Any, AsyncIterator

from .events import EventHub
from .figures import fenced_block
from .llm import Figure, LLMError, Thinking, Usage
from .sources import Sources
from .store import Store

log = logging.getLogger(__name__)

SAVE_INTERVAL = 1.5  # seconds between saves of a streaming answer (each announced as a change)
TERMINAL = ("done", "error")

Event = tuple[str, dict[str, Any]]


def _block_separator(text: str) -> str:
    """What goes between the answer so far and a block that must start on a fresh line after a blank one."""
    if not text.strip():
        return ""
    if text.endswith("\n\n"):
        return ""
    return "\n" if text.endswith("\n") else "\n\n"


class AnswerBuilder:
    """Accumulates an answer from model stream items and says which SSE event each one becomes."""

    def __init__(self) -> None:
        self.parts: list[str] = []
        self.thinking_parts: list[str] = []
        self.usage = Usage(prompt_tokens=0, completion_tokens=0)
        self.sources = Sources()
        self._after_figure = False

    @property
    def text(self) -> str:
        return "".join(self.parts)

    @property
    def thinking(self) -> str:
        return "".join(self.thinking_parts)

    def feed(self, item: Any) -> Event | None:
        """Take one stream item; returns the `thinking` / `delta` event to send, or None."""
        if isinstance(item, Usage):
            self.usage = item
            return None
        if isinstance(item, Sources):
            self.sources = self.sources.merge(item)
            return None
        if isinstance(item, Thinking):
            self.thinking_parts.append(item.text)
            return "thinking", {"text": item.text}
        if isinstance(item, Figure):
            # A figure is a fenced block of its own, one delta, on its own lines between paragraphs.
            text = _block_separator(self.text) + fenced_block(item.figure)
            self._after_figure = True
        else:
            text = item
            if self._after_figure and item.strip():
                text = ("\n" if item.startswith("\n") else "\n\n") + item.lstrip(" ")
                self._after_figure = False
            elif self._after_figure:
                return None  # whitespace right after a figure: the separator comes with the next text
        self.parts.append(text)
        return "delta", {"text": text}

    def done_payload(self, model: str) -> dict[str, Any]:
        return {
            "text": self.text,
            "model": model,
            "usage": self.usage.to_json(),
            "thinking": self.thinking,
            "sources": self.sources.to_json(),
        }


def error_payload(exc: LLMError) -> dict[str, Any]:
    payload: dict[str, Any] = {"error": exc.code, "message": exc.message}
    if exc.retry_after:
        payload["retry_after"] = exc.retry_after
    return payload


class Answer:
    """One assistant message being generated."""

    def __init__(
        self,
        *,
        answer_id: str,
        user_id: str,
        model: str,
        messages: list[dict[str, Any]],
        llm: Any,
        store: Store,
        hub: EventHub,
        effort: str | None = None,
        web_search: bool | None = None,
        figures: bool | None = None,
        save_interval: float = SAVE_INTERVAL,
    ) -> None:
        self.answer_id = answer_id
        self.user_id = user_id
        self.model = model
        self._messages = messages
        self._llm = llm
        self._store = store
        self._hub = hub
        self._options = {"effort": effort, "web_search": web_search, "figures": figures}
        self._save_interval = save_interval
        self.builder = AnswerBuilder()
        self.events: list[Event] = []
        self.finished = False
        self.task: asyncio.Task[None] | None = None
        self._wake = asyncio.Event()
        self._dirty = False
        self._started = False  # the task has begun running (cancelling before that would skip its body)
        self._streaming = False
        self._stop_reason = ""

    # ---- control ---------------------------------------------------------------

    def start(self) -> None:
        self._streaming = True
        self.task = asyncio.create_task(self._run(), name=f"answer-{self.answer_id}")

    def stop(self, reason: str = "stop") -> None:
        """End the answer early. "stop": it is saved as done with what arrived; "deleted": it was deleted;
        "shutdown": the server is stopping and it is saved as an error."""
        if self.task is None or not self._streaming or self._stop_reason:
            return
        self._stop_reason = reason
        if self._started:
            self.task.cancel()  # otherwise the task sees the reason when it begins

    async def wait(self, timeout: float | None = None) -> None:
        if self.task is not None:
            with suppress(asyncio.TimeoutError, asyncio.CancelledError):
                await asyncio.wait_for(asyncio.shield(self.task), timeout)

    # ---- listeners -------------------------------------------------------------

    async def listen(self) -> AsyncIterator[Event]:
        """Every event of the answer from the start (a late listener catches up), ending with done or error."""
        index = 0
        while True:
            wake = self._wake
            while index < len(self.events):
                event = self.events[index]
                index += 1
                yield event
                if event[0] in TERMINAL:
                    return
            if self.finished:
                return
            await wake.wait()

    def _emit(self, name: str, data: dict[str, Any]) -> None:
        self.events.append((name, data))
        wake, self._wake = self._wake, asyncio.Event()
        wake.set()

    # ---- the task --------------------------------------------------------------

    async def _run(self) -> None:
        self._started = True
        error: LLMError | None = None
        flusher = asyncio.create_task(self._flush_periodically())
        try:
            if not self._stop_reason:
                # aclosing: a stopped answer closes the provider stream (and its HTTP response) right away
                async with aclosing(self._llm.stream(self._messages, self.model, **self._options)) as stream:
                    async for item in stream:
                        event = self.builder.feed(item)
                        if event is not None:
                            self._emit(*event)
                            self._dirty = True
        except asyncio.CancelledError:
            current = asyncio.current_task()
            if not self._stop_reason:  # cancelled from outside (the event loop is closing): nothing can be saved
                self.finished = True
                self._emit("error", {"error": "internal_error", "message": "the answer was interrupted"})
                raise
            if current is not None:
                current.uncancel()
        except LLMError as exc:
            error = exc
        except Exception:
            log.exception("answer %s failed", self.answer_id)
            error = LLMError("internal_error", "chat stream failed")
        finally:
            self._streaming = False
            flusher.cancel()
            with suppress(asyncio.CancelledError):
                await flusher
        if self._stop_reason == "shutdown" and error is None:
            error = LLMError("internal_error", "the server stopped before the answer was finished")
        await self._finish(error)

    async def _flush_periodically(self) -> None:
        """Save the growing answer at most every `save_interval` seconds, so other devices see it grow."""
        while True:
            await asyncio.sleep(self._save_interval)
            if not self._dirty:
                continue
            self._dirty = False
            try:
                saved = await self._store.save_partial(self.answer_id, self.builder.text, self.builder.thinking)
            except Exception:
                log.exception("could not save the streaming answer %s", self.answer_id)
                continue
            if saved is not None:
                self._hub.publish(saved[0], saved[1])

    async def _finish(self, error: LLMError | None) -> None:
        builder = self.builder
        saved: tuple[dict[str, Any], int | None] | None = None
        try:
            saved = await self._store.finish_answer(
                self.answer_id, builder.text, builder.thinking, builder.sources.to_json(), error.code if error else ""
            )
        except Exception:
            log.exception("could not save the answer %s", self.answer_id)
            error = error or LLMError("internal_error", "the answer could not be saved")
        message, seq = saved if saved is not None else (None, None)
        self._hub.publish(self.user_id, seq)
        self.finished = True
        if error is not None:
            self._emit("error", error_payload(error))
            return
        payload = builder.done_payload(self.model)
        if message is not None and message["deleted"]:  # deleted while it streamed: nothing to show
            payload.update(text="", thinking="", sources=[])
        self._emit("done", {**payload, "message": message,
                            "seq": seq if seq is not None else (message or {}).get("seq")})


def replay(message: dict[str, Any]) -> list[Event]:
    """The terminal event of an answer that already finished (a repeated request after the fact)."""
    if message["status"] == "done":
        return [("done", {
            "text": message["content"], "model": message["model"],
            "usage": Usage(0, 0).to_json(), "thinking": message["thinking"], "sources": message["sources"],
            "message": message, "seq": message["seq"],
        })]
    return [("error", {"error": message["error"] or "internal_error", "message": "the answer failed"})]


class AnswerManager:
    """The answers being generated right now, by answer id."""

    def __init__(self) -> None:
        self._answers: dict[str, Answer] = {}

    def start(self, answer: Answer) -> Answer:
        self._answers[answer.answer_id] = answer
        answer.start()
        assert answer.task is not None
        answer.task.add_done_callback(lambda _: self._forget(answer))
        return answer

    def _forget(self, answer: Answer) -> None:
        if self._answers.get(answer.answer_id) is answer:
            del self._answers[answer.answer_id]

    def get(self, answer_id: str) -> Answer | None:
        return self._answers.get(answer_id)

    def running(self) -> list[Answer]:
        return list(self._answers.values())

    async def stop(self, answer_id: str, reason: str = "stop", timeout: float = 10.0) -> bool:
        """Stop an answer and wait (up to `timeout`) until it is saved. False when it was not running."""
        answer = self._answers.get(answer_id)
        if answer is None:
            return False
        answer.stop(reason)
        await answer.wait(timeout)
        return True

    def cancel(self, answer_ids: list[str], reason: str = "deleted") -> None:
        for answer_id in answer_ids:
            answer = self._answers.get(answer_id)
            if answer is not None:
                answer.stop(reason)

    async def shutdown(self, timeout: float = 5.0) -> None:
        answers = self.running()
        for answer in answers:
            answer.stop("shutdown")
        tasks = [a.task for a in answers if a.task is not None]
        if tasks:
            await asyncio.wait(tasks, timeout=timeout)

