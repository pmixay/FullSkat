"""FastAPI application: pairing and chat relay (SSE)."""

from __future__ import annotations

import json
import logging
import re
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Literal

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, ValidationError

from .auth import InvalidPasscode, StateStore, TokenRecord, token_hash
from .config import Settings, load_settings
from .llm import LLMError, Thinking, Usage, create_llm, trim_history
from .prompts import build_system_prompt
from .ratelimit import Decision, PairLimiter, SlidingWindowLimiter

log = logging.getLogger(__name__)

MAX_CHAT_BODY_BYTES = 64 * 1024
PASSCODE_RE = re.compile(r"^[0-9]{4,8}$")


# ---- errors ---------------------------------------------------------------


class ApiError(HTTPException):
    """HTTP error rendered as {"error": code, "message": text}."""

    def __init__(self, status_code: int, code: str, message: str, headers: dict[str, str] | None = None) -> None:
        super().__init__(status_code=status_code, detail=message, headers=headers)
        self.code = code


def _error_response(status_code: int, code: str, message: str, headers: dict[str, str] | None = None) -> JSONResponse:
    return JSONResponse({"error": code, "message": message}, status_code=status_code, headers=headers)


def _rate_limited(decision: Decision) -> ApiError:
    message = "too many failed pairing attempts, try later" if decision.reason == "locked" else "rate limit exceeded"
    return ApiError(429, decision.reason, message, headers={"Retry-After": str(decision.retry_after)})


# ---- request models -------------------------------------------------------


class PairRequest(BaseModel):
    passcode: str
    device_id: str = Field(min_length=1, max_length=128)
    device_name: str = Field(default="", max_length=128)


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    dialog_id: str = ""
    locale: str = Field(default="", max_length=32)
    model: str | None = None
    messages: list[ChatMessage] = Field(min_length=1)
    effort: Literal["low", "medium", "high", "xhigh", "max"] | None = None
    web_search: bool | None = None


# ---- helpers --------------------------------------------------------------


def client_ip(request: Request, settings: Settings) -> str:
    if settings.trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def _sse(event: str, data: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _default_effort(settings: Settings) -> str:
    """The server's default reasoning effort for the active provider ("" if none)."""
    if settings.llm_provider == "anthropic":
        return settings.anthropic_effort
    return settings.openrouter_reasoning_effort


def _limits(settings: Settings) -> dict[str, int]:
    return {
        "chat_per_min": settings.chat_per_min,
        "chat_per_day": settings.chat_per_day,
    }


# ---- app factory ----------------------------------------------------------


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        log.info(
            "FullSkat server ready: provider=%s model=%s web_search=%s data_dir=%s",
            settings.llm_provider,
            settings.chat_model,
            settings.web_search,
            settings.data_dir,
        )
        try:
            yield
        finally:
            close_llm = getattr(app.state.llm, "aclose", None)
            if close_llm is not None:
                await close_llm()
            await app.state.http.aclose()

    app = FastAPI(title="FullSkat server", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    http = httpx.AsyncClient(timeout=httpx.Timeout(10.0, read=120.0))
    app.state.settings = settings
    app.state.http = http
    app.state.store = StateStore(settings.data_dir / "state.json")
    app.state.llm = create_llm(settings, http)
    app.state.pair_limiter = PairLimiter(settings.pair_per_min, settings.pair_lock_failures, settings.pair_lock_minutes)
    app.state.chat_limiter = SlidingWindowLimiter(settings.chat_per_min, settings.chat_per_day)

    # ---- error handlers ----

    @app.exception_handler(HTTPException)
    async def _http_exception(_: Request, exc: HTTPException) -> JSONResponse:
        code = exc.code if isinstance(exc, ApiError) else "http_error"
        return _error_response(exc.status_code, code, str(exc.detail), exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(400, "invalid_request", _describe_validation(exc.errors()))

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.exception("Unhandled error", exc_info=exc)
        return _error_response(500, "internal_error", "internal server error")

    # ---- auth ----

    def authenticate(token: str | None) -> tuple[TokenRecord, str]:
        """Resolve a bearer token to its device record and rate-limit key."""
        if token is None:
            raise ApiError(401, "missing_token", "Authorization: Bearer <token> is required")
        record = app.state.store.lookup_token(token)
        if record is None:
            raise ApiError(401, "invalid_token", "token is unknown or was revoked")
        return record, token_hash(token)

    def require_auth(request: Request) -> tuple[TokenRecord, str]:
        return authenticate(_bearer_token(request.headers.get("authorization")))

    # ---- routes ----

    @app.get("/healthz")
    async def healthz() -> dict[str, bool]:
        return {"ok": True, "paired": app.state.store.is_paired}

    @app.post("/v1/pair")
    async def pair(request: Request) -> JSONResponse:
        ip = client_ip(request, settings)
        decision = app.state.pair_limiter.check(ip)
        if not decision.allowed:
            raise _rate_limited(decision)
        try:
            body = PairRequest.model_validate(await request.json())
        except ValueError as exc:
            raise ApiError(400, "invalid_request", _describe_validation(exc)) from exc
        if not PASSCODE_RE.fullmatch(body.passcode):
            raise ApiError(400, "invalid_passcode_format", "passcode must be 4 to 8 digits")
        try:
            result = await app.state.store.pair(body.passcode, body.device_id, body.device_name)
        except InvalidPasscode:
            app.state.pair_limiter.record_failure(ip)
            log.warning("Pairing failed for %s from %s", body.device_id, ip)
            raise ApiError(401, "invalid_passcode", "passcode does not match") from None
        app.state.pair_limiter.record_success(ip)
        payload = {
            "token": result.token,
            "first_setup": result.first_setup,
            "server_name": settings.server_name,
            "model": settings.chat_model,
            "models": settings.allowed_models,
            "effort": _default_effort(settings),
            "web_search": settings.web_search,
        }
        return JSONResponse(payload, status_code=201 if result.first_setup else 200)

    @app.get("/v1/me")
    async def me(auth: tuple[TokenRecord, str] = Depends(require_auth)) -> dict[str, Any]:
        record, _ = auth
        return {
            "device_id": record.device_id,
            "device_name": record.device_name,
            "model": settings.chat_model,
            "models": settings.allowed_models,
            "effort": _default_effort(settings),
            "web_search": settings.web_search,
            "limits": _limits(settings),
        }

    @app.post("/v1/chat")
    async def chat(request: Request, auth: tuple[TokenRecord, str] = Depends(require_auth)) -> StreamingResponse:
        record, key = auth
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > MAX_CHAT_BODY_BYTES:
            raise ApiError(413, "payload_too_large", "request body must be at most 64 KiB")
        raw = await request.body()
        if len(raw) > MAX_CHAT_BODY_BYTES:
            raise ApiError(413, "payload_too_large", "request body must be at most 64 KiB")
        decision = app.state.chat_limiter.check("chat", key)
        if not decision.allowed:
            raise _rate_limited(decision)
        try:
            body = ChatRequest.model_validate_json(raw)
        except ValidationError as exc:
            raise ApiError(400, "invalid_request", _describe_validation(exc)) from exc
        model = body.model or settings.chat_model
        if model not in settings.allowed_models:
            raise ApiError(400, "model_not_allowed", f"model {model!r} is not in the server allowlist")

        history = trim_history(
            [{"role": m.role, "content": m.content} for m in body.messages],
            settings.max_history_messages,
            settings.max_history_chars,
        )
        messages = [{"role": "system", "content": build_system_prompt(body.locale)}, *history]
        log.info("chat: device=%s model=%s messages=%d dialog=%s", record.device_id, model, len(history), body.dialog_id)

        async def events() -> AsyncIterator[str]:
            parts: list[str] = []
            thinking_parts: list[str] = []
            usage = Usage(prompt_tokens=0, completion_tokens=0)
            try:
                async for item in app.state.llm.stream(
                    messages, model, effort=body.effort, web_search=body.web_search
                ):
                    if isinstance(item, Usage):
                        usage = item
                        continue
                    if isinstance(item, Thinking):
                        thinking_parts.append(item.text)
                        yield _sse("thinking", {"text": item.text})
                        continue
                    parts.append(item)
                    yield _sse("delta", {"text": item})
            except LLMError as exc:
                yield _sse("error", {"error": exc.code, "message": exc.message})
                return
            except Exception:
                log.exception("chat stream failed")
                yield _sse("error", {"error": "internal_error", "message": "chat stream failed"})
                return
            done = {
                "text": "".join(parts),
                "model": model,
                "usage": usage.to_json(),
                "thinking": "".join(thinking_parts),
            }
            yield _sse("done", done)

        headers = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}
        return StreamingResponse(events(), media_type="text/event-stream", headers=headers)

    return app


def _describe_validation(errors: ValidationError | list[dict[str, Any]] | Exception) -> str:
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
