"""In-memory sliding-window rate limiting (monotonic clock, no external state)."""

from __future__ import annotations

import math
import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import Callable

MINUTE = 60.0
DAY = 86_400.0


@dataclass(frozen=True)
class Decision:
    allowed: bool
    retry_after: int = 0  # seconds, meaningful when not allowed
    reason: str = ""  # "rate_limited" | "locked" | ""


class SlidingWindowLimiter:
    """Allows at most `per_minute` hits in any 60 s window and `per_day` in any 24 h window per key.

    Keys are (scope, key) tuples so one limiter can serve several endpoints.
    """

    def __init__(
        self,
        per_minute: int | None,
        per_day: int | None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.per_minute = per_minute
        self.per_day = per_day
        self._clock = clock
        self._lock = threading.Lock()
        self._hits: dict[tuple[str, str], deque[float]] = {}

    def _prune(self, hits: deque[float], now: float) -> None:
        horizon = now - DAY
        while hits and hits[0] <= horizon:
            hits.popleft()

    def check(self, scope: str, key: str) -> Decision:
        """Record a hit if allowed; otherwise report when the next hit could succeed."""
        now = self._clock()
        with self._lock:
            hits = self._hits.setdefault((scope, key), deque())
            self._prune(hits, now)
            retry = 0.0
            if self.per_day is not None and len(hits) >= self.per_day:
                retry = max(retry, hits[len(hits) - self.per_day] + DAY - now)
            if self.per_minute is not None:
                minute_horizon = now - MINUTE
                recent = [t for t in hits if t > minute_horizon]
                if len(recent) >= self.per_minute:
                    retry = max(retry, recent[len(recent) - self.per_minute] + MINUTE - now)
            if retry > 0:
                return Decision(allowed=False, retry_after=max(1, math.ceil(retry)), reason="rate_limited")
            hits.append(now)
            return Decision(allowed=True)

    def reset(self, scope: str, key: str) -> None:
        with self._lock:
            self._hits.pop((scope, key), None)


class PairLimiter:
    """Per-IP pairing limiter: N attempts/min plus a lockout after consecutive failures."""

    def __init__(
        self,
        per_minute: int,
        lock_failures: int,
        lock_minutes: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._attempts = SlidingWindowLimiter(per_minute=per_minute, per_day=None, clock=clock)
        self.lock_failures = lock_failures
        self.lock_seconds = lock_minutes * MINUTE
        self._clock = clock
        self._lock = threading.Lock()
        self._failures: dict[str, int] = {}
        self._locked_until: dict[str, float] = {}

    def check(self, ip: str) -> Decision:
        """Call before attempting to pair. Locked IPs are rejected before the attempt is counted."""
        now = self._clock()
        with self._lock:
            until = self._locked_until.get(ip)
            if until is not None:
                if now < until:
                    return Decision(allowed=False, retry_after=max(1, math.ceil(until - now)), reason="locked")
                del self._locked_until[ip]
                self._failures.pop(ip, None)
        return self._attempts.check("pair", ip)

    def record_failure(self, ip: str) -> None:
        with self._lock:
            count = self._failures.get(ip, 0) + 1
            self._failures[ip] = count
            if count >= self.lock_failures:
                self._locked_until[ip] = self._clock() + self.lock_seconds

    def record_success(self, ip: str) -> None:
        with self._lock:
            self._failures.pop(ip, None)
            self._locked_until.pop(ip, None)
