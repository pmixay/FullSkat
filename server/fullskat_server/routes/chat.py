"""Chat: the v2 flow (answers generated and saved on the server), the legacy stateless shape and stop."""

from __future__ import annotations

import asyncio
import logging
from typing import Any, AsyncIterator, Iterable, Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .. import images
from ..answers import Answer, AnswerBuilder, Event, error_payload, replay
from ..api import SSE_HEADERS, ApiError, Auth, check_id, parse_json, rate_limited, read_body, require_auth, sse, validate
from ..llm import ChatImage, LLMError, trim_history
from ..prompts import build_system_prompt

log = logging.getLogger(__name__)

router = APIRouter()

MAX_CHAT_BODY_BYTES = 64 * 1024
MAX_ATTACHMENTS = 4
IMAGE_PLACEHOLDER = "[image]"

Effort = Literal["none", "minimal", "low", "medium", "high", "xhigh", "max"]


class Question(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    content: str = ""
    attachments: list[str] = Field(default_factory=list, max_length=MAX_ATTACHMENTS)
    created_at: int | None = Field(default=None, ge=0)


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    dialog_id: str
    message: Question
    answer_id: str
    locale: str = Field(default="", max_length=32)
    model: str | None = None
    effort: Effort | None = None
    web_search: bool | None = None
    figures: bool | None = None


class LegacyMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class LegacyChatRequest(BaseModel):
    """What watch builds from before v2 send: the whole history, nothing is saved."""

    dialog_id: str = ""
    locale: str = Field(default="", max_length=32)
    model: str | None = None
    messages: list[LegacyMessage] = Field(min_length=1)
    effort: Effort | None = None
    web_search: bool | None = None
    figures: bool | None = None


def _first(*values: Any) -> Any:
    """The first value that is not None (request, then prefs; None leaves it to the server default)."""
    return next((value for value in values if value is not None), None)


async def resolve_model(request: Request, requested: str | None, preferred: str | None) -> str:
    """The model to answer with: the requested one (checked), else the preferred one if it works, else the default."""
    state = request.app.state
    default, available = await state.llm.models()
    if not available:
        raise ApiError(503, "no_provider", "no model provider is working right now")
    if requested:
        if requested in available:
            return requested
        if state.settings.provider_for(requested) in state.settings.configured_providers:
            raise ApiError(503, "provider_unavailable", f"the provider of {requested!r} is not working right now")
        raise ApiError(400, "model_not_allowed", f"model {requested!r} is not in the server allowlist")
    return preferred if preferred in available else default


async def build_history(request: Request, user_id: str, dialog_id: str, answer_id: str) -> list[dict[str, Any]]:
    """The dialog as stored on the server, trimmed, with the images of the newest turns that have any."""
    state = request.app.state
    settings = state.settings
    rows = await state.store.history(dialog_id, answer_id, settings.max_history_messages)
    with_images = [i for i, row in enumerate(rows) if row["role"] == "user" and row["attachments"]]
    inline = set(with_images[len(with_images) - settings.max_history_image_messages:]) \
        if settings.max_history_image_messages else set()
    turns: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        turn: dict[str, Any] = {"role": row["role"], "content": row["content"]}
        if index in inline:
            turn["images"] = row["attachments"]  # references for now; trim_history only counts them
        elif row["attachments"]:
            turn["content"] = _with_placeholders(row["content"], len(row["attachments"]))
        turns.append(turn)
    trimmed = trim_history(turns, settings.max_history_messages, settings.max_history_chars)

    def load() -> list[dict[str, Any]]:
        for turn in trimmed:
            refs = turn.pop("images", None)
            if not refs:
                continue
            loaded = [
                ChatImage(data) for ref in refs
                if (data := images.read_base64(settings.data_dir, user_id, ref["id"])) is not None
            ]
            if loaded:
                turn["images"] = loaded
            if len(loaded) < len(refs):
                turn["content"] = _with_placeholders(turn["content"], len(refs) - len(loaded))
        return trimmed

    return await asyncio.to_thread(load)


def _with_placeholders(content: str, count: int) -> str:
    marks = " ".join([IMAGE_PLACEHOLDER] * count)
    return f"{content}\n{marks}" if content else marks


async def _frames(events: AsyncIterator[Event]) -> AsyncIterator[str]:
    async for name, data in events:
        yield sse(name, data)


async def _iterate(events: Iterable[Event]) -> AsyncIterator[Event]:
    for event in events:
        yield event


@router.post("/v1/chat")
async def chat(request: Request, auth: Auth = Depends(require_auth)) -> StreamingResponse:
    state = request.app.state
    settings = state.settings
    raw = await read_body(request, MAX_CHAT_BODY_BYTES)
    decision = state.chat_limiter.check("chat", auth.user_id)
    if not decision.allowed:
        raise rate_limited(decision)
    data = parse_json(raw)
    if isinstance(data, dict) and "messages" in data:
        return await legacy_chat(request, auth, validate(LegacyChatRequest, data))
    body: ChatRequest = validate(ChatRequest, data)
    question = body.message
    check_id(body.dialog_id, "dialog_id")
    check_id(question.id, "message.id")
    check_id(body.answer_id, "answer_id")
    if body.answer_id == question.id:
        raise ApiError(400, "invalid_request", "answer_id must differ from message.id")
    if len(set(question.attachments)) != len(question.attachments):
        raise ApiError(400, "invalid_request", "an attachment is listed twice")
    if not question.content.strip() and not question.attachments:
        raise ApiError(400, "invalid_request", "message.content is empty")

    prefs, _ = await state.store.get_prefs(auth.user_id)
    model = await resolve_model(request, body.model, prefs["model"])
    effort = _first(body.effort, prefs["effort"])
    web_search = _first(body.web_search, prefs["web_search"])
    figures = _first(body.figures, prefs["figures"])

    begun = await state.store.begin_answer(
        auth.user_id, body.dialog_id, question.id, question.content, question.attachments, question.created_at,
        body.answer_id, model,
    )
    state.hub.publish(auth.user_id, begun.seq)
    if begun.existing:  # the same request again: show the running answer, or how it ended
        running = state.answers.get(body.answer_id)
        source = running.listen() if running is not None else _iterate(replay(begun.answer))
        return StreamingResponse(_frames(source), media_type="text/event-stream", headers=SSE_HEADERS)

    history = await build_history(request, auth.user_id, body.dialog_id, body.answer_id)
    draws = settings.figures and figures is not False
    system = build_system_prompt(body.locale, figures=draws, device=auth.device["kind"])
    answer = state.answers.start(Answer(
        answer_id=body.answer_id,
        user_id=auth.user_id,
        model=model,
        messages=[{"role": "system", "content": system}, *history],
        llm=state.llm,
        store=state.store,
        hub=state.hub,
        effort=effort,
        web_search=web_search,
        figures=figures,
        save_interval=state.timing.save_interval,
    ))
    log.info("chat: user=%s device=%s model=%s history=%d dialog=%s",
             auth.user_id, auth.device_id, model, len(history), body.dialog_id)
    return StreamingResponse(_frames(answer.listen()), media_type="text/event-stream", headers=SSE_HEADERS)


async def legacy_chat(request: Request, auth: Auth, body: LegacyChatRequest) -> StreamingResponse:
    """The pre-v2 chat: history from the client, the answer streamed and forgotten."""
    state = request.app.state
    settings = state.settings
    model = await resolve_model(request, body.model, None)
    history = trim_history(
        [{"role": m.role, "content": m.content} for m in body.messages],
        settings.max_history_messages,
        settings.max_history_chars,
    )
    draws = settings.figures and body.figures is not False
    system = build_system_prompt(body.locale, figures=draws, device=auth.device["kind"])
    messages = [{"role": "system", "content": system}, *history]
    log.info("legacy chat: user=%s device=%s model=%s messages=%d", auth.user_id, auth.device_id, model, len(history))

    async def events() -> AsyncIterator[str]:
        builder = AnswerBuilder()
        try:
            async for item in state.llm.stream(
                messages, model, effort=body.effort, web_search=body.web_search, figures=body.figures
            ):
                event = builder.feed(item)
                if event is not None:
                    yield sse(*event)
        except LLMError as exc:
            yield sse("error", error_payload(exc))
            return
        except Exception:
            log.exception("chat stream failed")
            yield sse("error", {"error": "internal_error", "message": "chat stream failed"})
            return
        yield sse("done", builder.done_payload(model))

    return StreamingResponse(events(), media_type="text/event-stream", headers=SSE_HEADERS)


@router.post("/v1/messages/{message_id}/stop", status_code=204)
async def stop(message_id: str, request: Request, auth: Auth = Depends(require_auth)) -> Response:
    """End a streaming answer with what it has (status done). A finished answer is left as it is."""
    state = request.app.state
    running = state.answers.get(message_id)
    if running is not None and running.user_id == auth.user_id:
        await state.answers.stop(message_id)
        return Response(status_code=204)
    if await state.store.get_message(auth.user_id, message_id) is None:
        raise ApiError(404, "not_found", "no such message")
    return Response(status_code=204)
