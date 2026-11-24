"""What the routes share: errors, authentication, request reading, SSE framing and the model info block."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, ValidationError

from .auth import token_hash
from .config import Settings
from .ratelimit import Decision
from .store import StoreError

MAX_JSON_BYTES = 16 * 1024  # bodies of the small JSON endpoints
PASSCODE_RE = re.compile(r"^[0-9]{4,8}$")
# Client-generated ids (dialogs, messages): UUIDs in practice; letters, digits, "-" and "_" are accepted.
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
DEVICE_KINDS = ("phone", "watch", "other")
NAME_MAX = 40


class ApiError(HTTPException):
    """HTTP error rendered as {"error": code, "message": text}."""

    def __init__(self, status_code: int, code: str, message: str, headers: dict[str, str] | None = None) -> None:
        super().__init__(status_code=status_code, detail=message, headers=headers)
        self.code = code


def from_store(exc: StoreError) -> ApiError:
    return ApiError(exc.status, exc.code, exc.message)


def rate_limited(decision: Decision) -> ApiError:
    messages = {
        "locked": "too many failed attempts, try later",
        "daily_limit": "daily message limit reached",
    }
    message = messages.get(decision.reason, "rate limit exceeded")
    return ApiError(429, decision.reason, message, headers={"Retry-After": str(decision.retry_after)})


def describe_validation(errors: ValidationError | list[dict[str, Any]] | Exception) -> str:
    """One-line human summary of a pydantic validation failure."""
    if isinstance(errors, ValidationError):
        items = errors.errors()
    elif isinstance(errors, list):
        items = errors
    else:
        return str(errors) or "invalid request"
    parts = []
    for item in items[:3]:
        location = ".".join(str(p) for p in item.get("loc", ()) if p != "body")
        parts.append(f"{location}: {item.get('msg', 'invalid')}" if location else str(item.get("msg", "invalid")))
    return "; ".join(parts) or "invalid request"


# ---- requests -----------------------------------------------------------------


def client_ip(request: Request, settings: Settings) -> str:
    if settings.trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


async def read_body(request: Request, limit: int) -> bytes:
    """The request body, refused with 413 as soon as it grows past `limit` bytes (not read to the end)."""
    size = f"{limit // (1024 * 1024)} MB" if limit >= 1024 * 1024 else f"{limit // 1024} KiB"
    too_large = ApiError(413, "payload_too_large", f"request body must be at most {size}")
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise too_large
    body = bytearray()
    async for chunk in request.stream():
        body += chunk
        if len(body) > limit:
            raise too_large
    return bytes(body)


def parse_json(raw: bytes) -> Any:
    try:
        return json.loads(raw) if raw.strip() else {}
    except ValueError as exc:
        raise ApiError(400, "invalid_request", "body is not valid JSON") from exc


async def read_json(request: Request, limit: int = MAX_JSON_BYTES) -> Any:
    return parse_json(await read_body(request, limit))


def validate(model: type[BaseModel], data: Any) -> Any:
    try:
        return model.model_validate(data)
    except ValidationError as exc:
        raise ApiError(400, "invalid_request", describe_validation(exc)) from exc


def check_id(value: str, what: str) -> str:
    if not ID_RE.fullmatch(value):
        raise ApiError(400, "invalid_request", f"{what} must be 1 to 64 letters, digits, '-' or '_'")
    return value


def check_name(value: str) -> str:
    name = " ".join(value.split())
    if not 1 <= len(name) <= NAME_MAX:
        raise ApiError(400, "invalid_request", f"name must be 1 to {NAME_MAX} characters")
    return name


# ---- authentication -----------------------------------------------------------


@dataclass(frozen=True)
class Auth:
    """The caller: its user and device rows and the hash of its token."""

    user: dict[str, Any]
    device: dict[str, Any]
    token_hash: str

    @property
    def user_id(self) -> str:
        return str(self.user["id"])

    @property
    def device_id(self) -> str:
        return str(self.device["id"])


def bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


async def require_auth(request: Request) -> Auth:
    """FastAPI dependency: resolve the bearer token to (user, device) or answer 401."""
    token = bearer_token(request.headers.get("authorization"))
    if token is None:
        raise ApiError(401, "missing_token", "Authorization: Bearer <token> is required")
    hashed = token_hash(token)
    found = await request.app.state.store.resolve_token(hashed)
    if found is None:
        raise ApiError(401, "invalid_token", "token is unknown or was revoked")
    return Auth(found[0], found[1], hashed)


# ---- responses ----------------------------------------------------------------


SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


def sse(event: str, data: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


async def model_info(app: FastAPI) -> dict[str, Any]:
    """Default model, models of the working providers, per model the efforts it supports, and the switches."""
    settings: Settings = app.state.settings
    default, models = await app.state.llm.models()
    efforts = await app.state.llm.model_efforts(models)
    info = efforts.get(default)
    return {
        "model": default,
        "models": models,
        "effort": info.default if info else "",
        "model_efforts": {model: e.to_json() for model, e in efforts.items()},
        "providers": await app.state.llm.providers_info(),
        "web_search": settings.web_search,
        "figures": settings.figures,
    }


def limits(settings: Settings) -> dict[str, int]:
    return {"chat_per_min": settings.chat_per_min, "chat_per_day": settings.chat_per_day}
