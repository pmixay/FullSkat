"""Passcode hashing, bearer tokens and the reader for the pre-v2 pairing state (DATA_DIR/state.json)."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import secrets
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

PBKDF2_ITERATIONS = 200_000
SALT_BYTES = 16


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
class LegacyToken:
    """A token issued by the old `POST /v1/pair`, as state.json kept it (hashed)."""

    token_hash: str
    device_id: str
    device_name: str
    created_at: str  # ISO-8601 UTC, may be empty


@dataclass(frozen=True)
class LegacyState:
    passcode: PasscodeRecord | None
    tokens: list[LegacyToken]


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
    """Constant-time comparison of a candidate passcode against a stored record (CPU-bound, like hash_passcode)."""
    salt = base64.b64decode(record.salt)
    expected = base64.b64decode(record.hash)
    candidate = _derive(passcode, salt, record.iterations)
    return hmac.compare_digest(candidate, expected)


def generate_token() -> str:
    """256-bit URL-safe random bearer token (also used for link ids)."""
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    """Tokens are stored hashed; a leaked database must not yield usable credentials."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def read_legacy_state(path: Path) -> LegacyState:
    """Parse the old pairing state file.

    Layout: {"passcode": {salt, hash, iterations, set_at} | null,
             "tokens": {<sha256 of token>: {device_id, device_name, created_at}}}
    Raises OSError / ValueError when the file cannot be read or parsed.
    """
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("state file is not a JSON object")
    passcode = raw.get("passcode")
    tokens: list[LegacyToken] = []
    for key, value in (raw.get("tokens") or {}).items():
        value = value if isinstance(value, dict) else {}
        tokens.append(LegacyToken(
            token_hash=str(key),
            device_id=str(value.get("device_id") or ""),
            device_name=str(value.get("device_name") or ""),
            created_at=str(value.get("created_at") or ""),
        ))
    return LegacyState(PasscodeRecord.from_json(passcode) if passcode else None, tokens)
