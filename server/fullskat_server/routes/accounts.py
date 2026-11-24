"""Sign-up, the session, devices and linking a new device with a code."""

from __future__ import annotations

import asyncio
import logging
import time
from contextlib import suppress
from typing import Any, Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from .. import linking
from ..api import (
    PASSCODE_RE,
    ApiError,
    Auth,
    check_name,
    client_ip,
    limits,
    model_info,
    rate_limited,
    read_json,
    require_auth,
    validate,
)
from ..auth import generate_token, hash_passcode, token_hash, verify_passcode
from ..store import device_json, user_json

log = logging.getLogger(__name__)

router = APIRouter()

DeviceKind = Literal["phone", "watch", "other"]


class RegisterRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    passcode: str
    name: str = Field(max_length=200)
    device_id: str = Field(min_length=1, max_length=128)
    device_name: str = Field(default="", max_length=128)
    device_kind: DeviceKind = "phone"


class LinkStartRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    device_id: str = Field(min_length=1, max_length=128)
    device_name: str = Field(default="", max_length=128)
    device_kind: DeviceKind = "watch"


class ClaimRequest(BaseModel):
    code: str = Field(max_length=32)


class RenameRequest(BaseModel):
    name: str = Field(max_length=200)


# ---- health and the retired pairing ---------------------------------------------


@router.get("/healthz")
async def healthz(request: Request) -> dict[str, Any]:
    return {"ok": True, "users": await request.app.state.store.count_users()}


@router.post("/v1/pair")
async def pair() -> None:
    raise ApiError(410, "gone", "pairing was replaced by accounts: link this device with a code (POST /v1/link/start)")


# ---- sign-up ----------------------------------------------------------------------


async def _check_passcode(request: Request, passcode: str) -> bool | None:
    """True: this call set the passcode (first setup); False: it matches; None: it does not match."""
    state = request.app.state
    async with state.passcode_lock:
        record = await state.store.get_passcode()
        if record is None:
            fresh = await asyncio.to_thread(hash_passcode, passcode)
            if await state.store.set_passcode(fresh):
                return True
            record = await state.store.get_passcode()
    return False if await asyncio.to_thread(verify_passcode, passcode, record) else None


@router.post("/v1/register")
async def register(request: Request) -> JSONResponse:
    state = request.app.state
    settings = state.settings
    ip = client_ip(request, settings)
    decision = state.register_limiter.check(ip)
    if not decision.allowed:
        raise rate_limited(decision)
    body = validate(RegisterRequest, await read_json(request))
    if not PASSCODE_RE.fullmatch(body.passcode):
        raise ApiError(400, "invalid_passcode_format", "passcode must be 4 to 8 digits")
    name = check_name(body.name)
    first_setup = await _check_passcode(request, body.passcode)
    if first_setup is None:
        state.register_limiter.record_failure(ip)
        log.warning("Sign-up with a wrong passcode from %s", ip)
        raise ApiError(401, "invalid_passcode", "passcode does not match")
    state.register_limiter.record_success(ip)
    token = generate_token()
    user, device, created = await state.store.register(
        name, body.device_id, body.device_name, body.device_kind, token_hash(token)
    )
    if not created:
        state.hub.revoke_device(device["id"])  # its old token no longer works
    log.info("Registered device %s (%s) for user %s, new user=%s, first_setup=%s",
             device["id"], device["kind"], user["id"], created, first_setup)
    payload = {
        "token": token,
        "first_setup": first_setup,
        "server_name": settings.server_name,
        "user": user_json(user),
        "device": device_json(device, device["id"]),
        **await model_info(request.app),
    }
    return JSONResponse(payload, status_code=201 if created else 200)


# ---- the session and devices ------------------------------------------------------


@router.get("/v1/me")
async def me(request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    settings = request.app.state.settings
    return {
        "user": user_json(auth.user),
        "device": device_json(auth.device, auth.device_id),
        "server_name": settings.server_name,
        **await model_info(request.app),
        "limits": limits(settings),
    }


@router.patch("/v1/me")
async def rename(request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    body = validate(RenameRequest, await read_json(request))
    user = await request.app.state.store.rename_user(auth.user_id, check_name(body.name))
    return {"user": user_json(user)}


@router.get("/v1/devices")
async def devices(request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    rows = await request.app.state.store.list_devices(auth.user_id)
    return {"devices": [device_json(row, auth.device_id) for row in rows]}


@router.delete("/v1/devices/{device_id}", status_code=204)
async def unlink(device_id: str, request: Request, auth: Auth = Depends(require_auth)) -> Response:
    if not await request.app.state.store.delete_device(auth.user_id, device_id):
        raise ApiError(404, "not_found", "no such device")
    request.app.state.hub.revoke_device(device_id)
    log.info("User %s unlinked device %s", auth.user_id, device_id)
    return Response(status_code=204)


# ---- linking with a code ------------------------------------------------------------


@router.post("/v1/link/start")
async def link_start(request: Request) -> dict[str, Any]:
    state = request.app.state
    decision = state.link_start_limiter.check("link_start", client_ip(request, state.settings))
    if not decision.allowed:
        raise rate_limited(decision)
    body = validate(LinkStartRequest, await read_json(request))
    link_id = generate_token()
    code, t = await state.store.create_link(token_hash(link_id), body.device_id, body.device_name, body.device_kind)
    return {"link_id": link_id, "code": code, "expires_in": linking.expires_in(t), "period": linking.PERIOD}


def _pending(code: str, t: float) -> dict[str, Any]:
    return {"status": "pending", "code": code, "expires_in": linking.expires_in(t), "period": linking.PERIOD}


@router.get("/v1/link/status")
async def link_status(request: Request, link_id: str = "", wait: int = 0) -> dict[str, Any]:
    """Long-poll: answers at once when the device has not heard the current code yet or the link was claimed,
    else when the code rotates, the link is claimed, or `wait` seconds pass."""
    state = request.app.state
    expired = ApiError(404, "link_expired", "the link expired; start over")
    if not link_id or len(link_id) > 256:
        raise expired
    key = token_hash(link_id)
    deadline = time.monotonic() + max(0, min(wait, linking.MAX_WAIT))
    while True:
        woken = state.link_waiters.add(key)  # before reading the link, so a claim in between is not missed
        try:
            link = await state.store.poll_link(key)
            if link is None:
                raise expired
            if link.claimed:
                info = await model_info(request.app)  # before the token exists: it is delivered only once
                token = generate_token()
                delivered = await state.store.deliver_link(key, token_hash(token))
                if delivered is None:
                    raise expired
                user, device = delivered
                log.info("Linked device %s (%s) to user %s", device["id"], device["kind"], user["id"])
                return {
                    "status": "linked",
                    "token": token,
                    "server_name": state.settings.server_name,
                    "user": user_json(user),
                    "device": device_json(device, device["id"]),
                    **info,
                }
            remaining = deadline - time.monotonic()
            if link.sent_window < link.window or remaining <= 0:
                await state.store.mark_link_sent(key, link.window)
                return _pending(link.code, link.t)
            until_rotation = (link.window + 1) * linking.PERIOD - link.t
            with suppress(TimeoutError):
                await asyncio.wait_for(woken.wait(), timeout=min(remaining, max(until_rotation, 0.01)))
        finally:
            state.link_waiters.discard(key, woken)


@router.post("/v1/link/claim")
async def link_claim(request: Request, auth: Auth = Depends(require_auth)) -> dict[str, Any]:
    state = request.app.state
    limiter = state.claim_limiter
    decision = limiter.check(auth.user_id)
    if not decision.allowed:
        raise rate_limited(decision)
    body = validate(ClaimRequest, await read_json(request))
    code = linking.normalize_code(body.code)
    claimed = await state.store.claim_link(auth.user_id, code, auth.device_id) if linking.is_code(code) else None
    if claimed is None:
        limiter.record_failure(auth.user_id)
        raise ApiError(404, "invalid_code", "wrong or expired code")
    limiter.record_success(auth.user_id)
    state.hub.revoke_device(claimed.device["id"])  # a known device loses its old token
    state.link_waiters.notify(claimed.link_key)
    return {"device": device_json(claimed.device)}
