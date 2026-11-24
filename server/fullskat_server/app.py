"""FastAPI application: accounts, code linking, sync, attachments and the chat relay (see docs/PROTOCOL.md)."""

from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import AsyncIterator, Callable

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .answers import SAVE_INTERVAL, AnswerManager
from .api import ApiError, describe_validation
from .config import Settings, load_settings
from .events import EventHub
from .linking import Waiters
from .llm import create_llm
from .ratelimit import PairLimiter, SlidingWindowLimiter
from .routes import accounts, chat, data, media
from .store import Store, StoreError

log = logging.getLogger(__name__)

PING_INTERVAL = 25.0  # seconds between `ping` events of /v1/events
COALESCE = 0.2  # seconds a burst of changes may take before one `changed` event goes out


@dataclass
class Timing:
    """Timings of the live parts; tests shorten them."""

    ping_interval: float = PING_INTERVAL
    coalesce: float = COALESCE
    save_interval: float = SAVE_INTERVAL


def _error_response(status_code: int, code: str, message: str, headers: dict[str, str] | None = None) -> JSONResponse:
    return JSONResponse({"error": code, "message": message}, status_code=status_code, headers=headers)


def create_app(settings: Settings | None = None, *, clock: Callable[[], float] | None = None) -> FastAPI:
    """Build the app. `clock` (unix seconds) is injectable so tests can move time (link codes, timestamps)."""
    settings = settings or load_settings()
    clock = clock or time.time
    store = Store(settings.data_dir / "fullskat.db", clock=clock)
    store.migrate_legacy_state(settings.data_dir / "state.json")
    store.fail_interrupted_answers()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        log.info(
            "FullSkat server ready: providers=%s web_search=%s data_dir=%s",
            ",".join(settings.configured_providers) or "none (set ANTHROPIC_API_KEY, OPENROUTER_API_KEY, KIMI_API_KEY or OPENAI_API_KEY)",
            settings.web_search,
            settings.data_dir,
        )
        try:
            yield
        finally:
            await app.state.answers.shutdown()
            close_llm = getattr(app.state.llm, "aclose", None)
            if close_llm is not None:
                await close_llm()
            await app.state.http.aclose()
            await asyncio.to_thread(app.state.store.close)

    app = FastAPI(title="FullSkat server", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    http = httpx.AsyncClient(timeout=httpx.Timeout(10.0, read=120.0))
    app.state.settings = settings
    app.state.clock = clock
    app.state.http = http
    app.state.store = store
    app.state.llm = create_llm(settings, http)
    app.state.hub = EventHub()
    app.state.link_waiters = Waiters()
    app.state.answers = AnswerManager()
    app.state.timing = Timing()
    app.state.passcode_lock = asyncio.Lock()
    app.state.register_limiter = PairLimiter(settings.pair_per_min, settings.pair_lock_failures, settings.pair_lock_minutes)
    app.state.link_start_limiter = SlidingWindowLimiter(settings.link_start_per_min, None)
    app.state.claim_limiter = PairLimiter(
        settings.link_claim_per_min, settings.link_claim_lock_failures, settings.link_claim_lock_minutes
    )
    app.state.chat_limiter = SlidingWindowLimiter(settings.chat_per_min, settings.chat_per_day)
    app.state.upload_limiter = SlidingWindowLimiter(None, None, per_hour=settings.uploads_per_hour)

    # ---- error handlers ----

    @app.exception_handler(StarletteHTTPException)
    async def _http_exception(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = exc.code if isinstance(exc, ApiError) else {404: "not_found", 405: "method_not_allowed"}.get(
            exc.status_code, "http_error"
        )
        return _error_response(exc.status_code, code, str(exc.detail), exc.headers)

    @app.exception_handler(StoreError)
    async def _store_error(_: Request, exc: StoreError) -> JSONResponse:
        return _error_response(exc.status, exc.code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(400, "invalid_request", describe_validation(exc.errors()))

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.exception("Unhandled error", exc_info=exc)
        return _error_response(500, "internal_error", "internal server error")

    for module in (accounts, data, chat, media):
        app.include_router(module.router)
    return app
