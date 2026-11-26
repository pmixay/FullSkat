"""Preferences, dialogs, messages, sync and the live event stream."""

from __future__ import annotations

import asyncio
import logging
from typing import Any, AsyncIterator

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .. import images
from ..api import SSE_HEADERS, ApiError, Auth, check_id, read_json, require_auth, sse, validate
from ..efforts import EFFORT_LEVELS
from ..store import Deleted

log = logging.getLogger(__name__)

router = APIRouter()

TITLE_MAX = 200
SYNC_LIMIT_MAX = 1000


class DialogCreate(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    title: str = Field(default="", max_length=TITLE_MAX)
    created_at: int | None = Field(default=None, ge=0)


class DialogPatch(BaseModel):
    title: str = Field(max_length=TITLE_MAX)


# ---- preferences --------------------------------------------------------------------


def _prefs_changes(data: Any, request: Request) -> dict[str, Any]:
    """The known keys of a PUT /v1/prefs body, checked; unknown keys are ignored."""
    if not isinstance(data, dict):
        raise ApiError(400, "invalid_request", "prefs must be a JSON object")
    settings = request.app.state.settings
    changes: dict[str, Any] = {}
    if "model" in data:
        model = data["model"]
        if model is not None and (not isinstance(model, str) or settings.provider_for(model) is None):
            raise ApiError(400, "invalid_request", f"model {model!r} is not offered by this server")
        changes["model"] = model
    if "effort" in data:
        effort = data["effort"]
        if effort is not None and effort not in EFFORT_LEVELS:
            raise ApiError(400, "invalid_request", f"effort must be one of {', '.join(EFFORT_LEVELS)} or null")
        changes["effort"] = effort
    for key in ("web_search", "figures"):
        if key in data:
            if data[key] is not None and not isinstance(data[key], bool):
                raise ApiError(400, "invalid_request", f"{key} must be true, false or null")
            changes[key] = data[key]
    return changes


@router.get("/v1/prefs")
async def get_prefs(request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    prefs, updated_at = await request.app.state.store.get_prefs(auth.user_id)
    return {"prefs": prefs, "updated_at": updated_at}


@router.put("/v1/prefs")
async def put_prefs(request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    changes = _prefs_changes(await read_json(request), request)
    prefs, updated_at, seq = await request.app.state.store.put_prefs(auth.user_id, changes)
    request.app.state.hub.publish(auth.user_id, seq)
    return {"prefs": prefs, "updated_at": updated_at}


# ---- dialogs and messages -----------------------------------------------------------


async def _after_delete(request: Request, auth: Auth, deleted: Deleted) -> None:
    """Stop answers that were deleted mid-stream, remove files no message uses, announce the change."""
    state = request.app.state
    state.answers.cancel(deleted.message_ids, reason="deleted")
    for user_id, attachment_id in deleted.orphans:
        await asyncio.to_thread(images.delete_files, state.settings.data_dir, user_id, attachment_id)
    state.hub.publish(auth.user_id, deleted.seq)


@router.post("/v1/dialogs")
async def create_dialog(request: Request, auth: Auth = Depends(require_auth)) -> JSONResponse:
    body = validate(DialogCreate, await read_json(request))
    check_id(body.id, "id")
    dialog, created, seq = await request.app.state.store.create_dialog(
        auth.user_id, body.id, body.title.strip(), body.created_at
    )
    request.app.state.hub.publish(auth.user_id, seq)
    return JSONResponse(dialog, status_code=201 if created else 200)


@router.patch("/v1/dialogs/{dialog_id}")
async def rename_dialog(dialog_id: str, request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    body = validate(DialogPatch, await read_json(request))
    dialog, seq = await request.app.state.store.rename_dialog(auth.user_id, dialog_id, body.title.strip())
    request.app.state.hub.publish(auth.user_id, seq)
    return dialog


@router.delete("/v1/dialogs/{dialog_id}", status_code=204)
async def delete_dialog(dialog_id: str, request: Request, auth: Auth = Depends(require_auth)) -> Response:
    deleted = await request.app.state.store.delete_dialog(auth.user_id, dialog_id)
    await _after_delete(request, auth, deleted)
    return Response(status_code=204)


@router.delete("/v1/messages/{message_id}", status_code=204)
async def delete_message(message_id: str, request: Request, auth: Auth = Depends(require_auth)) -> Response:
    deleted = await request.app.state.store.delete_message(auth.user_id, message_id)
    await _after_delete(request, auth, deleted)
    return Response(status_code=204)


# ---- sync -----------------------------------------------------------------------------


@router.get("/v1/sync")
async def sync(request: Request, since: int = 0, limit: int = 500, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    if since < 0:
        raise ApiError(400, "invalid_request", "since must be 0 or more")
    if not 1 <= limit <= SYNC_LIMIT_MAX:
        raise ApiError(400, "invalid_request", f"limit must be 1 to {SYNC_LIMIT_MAX}")
    return await request.app.state.store.sync(auth.user_id, since, limit)


# ---- live events ----------------------------------------------------------------------


@router.get("/v1/events")
async def events(request: Request, auth: Auth = Depends(require_auth)) -> StreamingResponse:
    state = request.app.state
    timing = state.timing

    async def stream() -> AsyncIterator[str]:
        subscription = state.hub.subscribe(auth.user_id, auth.device_id, 0)
        try:
            # Subscribed first, then read: a change in between is in `subscription.seq` either way.
            subscription.seq = max(subscription.seq, await state.store.user_seq(auth.user_id))
            sent = subscription.seq
            yield sse("changed", {"seq": sent})
            while True:
                woke = await subscription.wait(timing.ping_interval)
                if subscription.revoked:
                    yield sse("revoked", {})
                    return
                if not woke:
                    yield sse("ping", {})
                    continue
                await asyncio.sleep(timing.coalesce)  # let a burst of changes settle into one event
                if subscription.revoked:
                    yield sse("revoked", {})
                    return
                if subscription.seq > sent:
                    sent = subscription.seq
                    yield sse("changed", {"seq": sent})
        finally:
            state.hub.unsubscribe(subscription)

    return StreamingResponse(stream(), media_type="text/event-stream", headers=SSE_HEADERS)
