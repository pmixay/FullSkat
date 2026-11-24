"""Code linking: the rotating 6-character codes and the wake-ups of the long-polling device.

A new device starts a link and shows a code that a signed-in device types in. The code changes every
PERIOD seconds, aligned to the clock (window = floor(unix_seconds / PERIOD)). Each link's code for a window
is HMAC-SHA256(server secret, link key | window | salt) mapped onto ALPHABET; the store persists the code
of every (link, window) it hands out, and bumps the salt when another pending link already has that code in
that window, so two pending links never share a code and a shown code never changes.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import math

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I; 32 symbols = 5 bits each
CODE_LENGTH = 6
PERIOD = 30  # seconds per code window
GRACE = 5  # seconds after a rotation during which the previous window's code is still accepted
LINK_TTL = 600  # a pending link expires after this many seconds without a status poll
MAX_WAIT = 25  # longest long-poll, seconds

assert len(ALPHABET) == 32


def window_of(t: float) -> int:
    """Index of the code window that contains unix time `t` (seconds)."""
    return math.floor(t / PERIOD)


def expires_in(t: float) -> int:
    """Whole seconds left in the window of `t` (1..PERIOD)."""
    return max(1, min(PERIOD, math.ceil((window_of(t) + 1) * PERIOD - t)))


def in_grace(t: float) -> bool:
    """Whether `t` lies in the first GRACE seconds of its window."""
    return t - window_of(t) * PERIOD < GRACE


def derive_code(secret: bytes, link_key: str, window: int, salt: int = 0) -> str:
    """The code of one link in one window. 256 is a multiple of 32, so `byte % 32` is unbiased."""
    digest = hmac.new(secret, f"{link_key}|{window}|{salt}".encode("ascii"), hashlib.sha256).digest()
    return "".join(ALPHABET[b % len(ALPHABET)] for b in digest[:CODE_LENGTH])


def normalize_code(raw: str) -> str:
    """What a person typed, as a code: upper case, without spaces and dashes."""
    return "".join(ch for ch in raw.upper() if not ch.isspace() and ch != "-")


def is_code(code: str) -> bool:
    return len(code) == CODE_LENGTH and all(ch in ALPHABET for ch in code)


class Waiters:
    """Per-key asyncio events: a long-poll registers one before it checks the link, a claim sets them all."""

    def __init__(self) -> None:
        self._events: dict[str, set[asyncio.Event]] = {}

    def add(self, key: str) -> asyncio.Event:
        event = asyncio.Event()
        self._events.setdefault(key, set()).add(event)
        return event

    def discard(self, key: str, event: asyncio.Event) -> None:
        events = self._events.get(key)
        if events is not None:
            events.discard(event)
            if not events:
                del self._events[key]

    def notify(self, key: str) -> None:
        for event in self._events.get(key, ()):
            event.set()

    def count(self, key: str) -> int:
        return len(self._events.get(key, ()))
