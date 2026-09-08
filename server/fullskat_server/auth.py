"""Passcode hashing, bearer tokens and the persistent pairing state (DATA_DIR/state.json)."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

PBKDF2_ITERATIONS = 200_000
SALT_BYTES = 16


class InvalidPasscode(Exception):
    """Raised when the supplied passcode does not match the stored one."""


@dataclass(frozen=True)
class PasscodeRecord:
    salt: str  # base64
    hash: str  # base64
    iterations: int
    set_at: str  # ISO-8601 UTC

    def to_json(self) -> dict[str, Any]:
        return {"salt": self.salt, "hash": self.hash, "iterations": self.iterations, "set_at": self.set_at}

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> PasscodeRecord:
        return cls(
            salt=str(data["salt"]),
            hash=str(data["hash"]),
            iterations=int(data["iterations"]),
            set_at=str(data.get("set_at", "")),
        )


@dataclass(frozen=True)
class TokenRecord:
    device_id: str
    device_name: str
    created_at: str  # ISO-8601 UTC

    def to_json(self) -> dict[str, Any]:
        return {"device_id": self.device_id, "device_name": self.device_name, "created_at": self.created_at}

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> TokenRecord:
        return cls(
            device_id=str(data.get("device_id", "")),
            device_name=str(data.get("device_name", "")),
            created_at=str(data.get("created_at", "")),
        )


@dataclass(frozen=True)
class PairResult:
    token: str
    first_setup: bool


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _derive(passcode: str, salt: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", passcode.encode("utf-8"), salt, iterations)


def hash_passcode(passcode: str, iterations: int = PBKDF2_ITERATIONS) -> PasscodeRecord:
    """Hash a passcode with a fresh random salt. CPU-bound: call via asyncio.to_thread in async code."""
    salt = secrets.token_bytes(SALT_BYTES)
    digest = _derive(passcode, salt, iterations)
    return PasscodeRecord(
        salt=base64.b64encode(salt).decode("ascii"),
        hash=base64.b64encode(digest).decode("ascii"),
        iterations=iterations,
        set_at=_now_iso(),
    )


def verify_passcode(passcode: str, record: PasscodeRecord) -> bool:
    """Constant-time comparison of a candidate passcode against a stored record."""
    salt = base64.b64decode(record.salt)
    expected = base64.b64decode(record.hash)
    candidate = _derive(passcode, salt, record.iterations)
    return hmac.compare_digest(candidate, expected)


def generate_token() -> str:
    """256-bit URL-safe random bearer token."""
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    """Tokens are stored hashed; a leaked state file must not yield usable credentials."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class StateStore:
    """Pairing state persisted as JSON. Safe to use from threads and coroutines.

    Layout of the file:
        {"passcode": {salt, hash, iterations, set_at} | null,
         "tokens": {<sha256 of token>: {device_id, device_name, created_at}}}
    """

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self._lock = threading.Lock()
        self._async_lock = asyncio.Lock()
        self._passcode: PasscodeRecord | None = None
        self._tokens: dict[str, TokenRecord] = {}
        self._load()

    # ---- persistence -------------------------------------------------------

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            log.error("Cannot read state file %s (%s); starting with empty state", self.path, exc)
            return
        passcode = raw.get("passcode")
        self._passcode = PasscodeRecord.from_json(passcode) if passcode else None
        self._tokens = {
            str(key): TokenRecord.from_json(value) for key, value in (raw.get("tokens") or {}).items()
        }
        log.info("Loaded state from %s (paired=%s, tokens=%d)", self.path, self.is_paired, len(self._tokens))

    def _save_locked(self) -> None:
        """Write the state atomically: temp file in the same directory, then os.replace."""
        payload = {
            "passcode": self._passcode.to_json() if self._passcode else None,
            "tokens": {key: record.to_json() for key, record in self._tokens.items()},
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(f".{self.path.name}.{os.getpid()}.tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)
            fh.flush()
            os.fsync(fh.fileno())
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self.path)

    # ---- queries -----------------------------------------------------------

    @property
    def is_paired(self) -> bool:
        with self._lock:
            return self._passcode is not None

    def lookup_token(self, token: str) -> TokenRecord | None:
        with self._lock:
            return self._tokens.get(token_hash(token))

    # ---- mutations ---------------------------------------------------------

    async def pair(self, passcode: str, device_id: str, device_name: str) -> PairResult:
        """Set the passcode on first use, otherwise verify it; issue a token for the device.

        Raises InvalidPasscode on mismatch. Hashing runs off the event loop.
        """
        async with self._async_lock:
            with self._lock:
                current = self._passcode
            if current is None:
                record = await asyncio.to_thread(hash_passcode, passcode)
                first_setup = True
            else:
                if not await asyncio.to_thread(verify_passcode, passcode, current):
                    raise InvalidPasscode()
                record = current
                first_setup = False
            token = generate_token()
            with self._lock:
                if self._passcode is None:
                    self._passcode = record
                self._tokens = {k: v for k, v in self._tokens.items() if v.device_id != device_id}
                self._tokens[token_hash(token)] = TokenRecord(
                    device_id=device_id, device_name=device_name, created_at=_now_iso()
                )
                self._save_locked()
            log.info("Paired device %r (%s), first_setup=%s", device_name, device_id, first_setup)
            return PairResult(token=token, first_setup=first_setup)
