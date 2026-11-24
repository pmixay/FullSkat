"""Everything the server keeps, in DATA_DIR/fullskat.db: the passcode, users, devices, code links, dialogs,
messages, attachments, preferences and each user's change sequence (see docs/PROTOCOL.md, Sync).

Each public method is one transaction on the database thread (`offloaded`). Every change of a user's
dialog, message or prefs takes the next value of that user's sequence; methods that change something
return the new latest seq so the caller can announce it (events.EventHub).
"""

from __future__ import annotations

import json
import logging
import secrets
import sqlite3
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable

from . import linking
from .auth import PasscodeRecord, read_legacy_state
from .db import Database, offloaded

log = logging.getLogger(__name__)

Clock = Callable[[], float]  # unix seconds

TOUCH_INTERVAL_MS = 60_000  # last_seen is written at most once a minute
TITLE_LENGTH = 60
PREF_KEYS = ("model", "effort", "web_search", "figures")
DEFAULT_PREFS: dict[str, Any] = {key: None for key in PREF_KEYS}
LEGACY_USER_NAME = "Owner"


# ---- errors -------------------------------------------------------------------


class StoreError(Exception):
    """A request the data does not allow; rendered as {"error": code, "message": ...} with `status`."""

    status = 400
    code = "invalid_request"

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class NotFound(StoreError):
    status = 404
    code = "not_found"


class Conflict(StoreError):
    status = 409
    code = "conflict"


class InvalidRequest(StoreError):
    pass


# ---- results ------------------------------------------------------------------


@dataclass(frozen=True)
class LinkState:
    claimed: bool
    code: str = ""
    window: int = 0
    sent_window: int = -1
    t: float = 0.0  # the store's clock (unix seconds) when it was read


@dataclass(frozen=True)
class Claimed:
    link_key: str
    device: dict[str, Any]


@dataclass(frozen=True)
class Deleted:
    seq: int | None  # None: it was already deleted, nothing changed
    message_ids: list[str] = field(default_factory=list)  # messages that became tombstones
    orphans: list[tuple[str, str]] = field(default_factory=list)  # (user_id, attachment_id) whose files go


@dataclass(frozen=True)
class Begun:
    user_message: dict[str, Any]
    answer: dict[str, Any]
    existing: bool  # the answer was already there: a retry of the same request
    seq: int | None


# ---- JSON shapes --------------------------------------------------------------


def user_json(row: dict[str, Any]) -> dict[str, Any]:
    return {"id": row["id"], "name": row["name"], "created_at": row["created_at"]}


def device_json(row: dict[str, Any], current_id: str | None = None) -> dict[str, Any]:
    return {
        "id": row["id"], "kind": row["kind"], "name": row["name"],
        "created_at": row["created_at"], "last_seen": row["last_seen"], "current": row["id"] == current_id,
    }


def dialog_json(row: sqlite3.Row | dict[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"], "title": row["title"], "created_at": row["created_at"], "updated_at": row["updated_at"],
        "deleted": bool(row["deleted"]), "seq": row["seq"],
    }


def message_json(row: sqlite3.Row | dict[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"], "dialog_id": row["dialog_id"], "role": row["role"],
        "content": row["content"], "thinking": row["thinking"],
        "sources": json.loads(row["sources"]), "attachments": json.loads(row["attachments"]),
        "model": row["model"], "status": row["status"], "error": row["error"],
        "created_at": row["created_at"], "updated_at": row["updated_at"],
        "deleted": bool(row["deleted"]), "seq": row["seq"],
    }


def attachment_ref(row: sqlite3.Row | dict[str, Any]) -> dict[str, Any]:
    """How a message lists an attachment."""
    return {"id": row["id"], "mime": row["mime"], "width": row["width"], "height": row["height"]}


def auto_title(content: str) -> str:
    return " ".join(content.split())[:TITLE_LENGTH]


def _prefs(data: str | None) -> dict[str, Any]:
    stored = json.loads(data) if data else {}
    return {key: stored.get(key) for key in PREF_KEYS}


def _iso_to_ms(value: str, fallback: int) -> int:
    try:
        return int(datetime.fromisoformat(value).timestamp() * 1000)
    except (ValueError, TypeError):
        return fallback


def _placeholders(items: Iterable[Any]) -> str:
    return ",".join("?" for _ in items)


# ---- the store ----------------------------------------------------------------


class Store:
    def __init__(self, path: Path, clock: Clock = time.time) -> None:
        self.db = Database(path)
        self.clock = clock
        self._secret = self._load_secret()

    def close(self) -> None:
        self.db.close()

    def now(self) -> int:
        """Milliseconds since the epoch, from the injectable clock."""
        return int(self.clock() * 1000)

    def _load_secret(self) -> bytes:
        """The server secret behind link codes: generated once, kept in the database."""
        with self.db.tx() as con:
            row = con.execute("SELECT value FROM server_meta WHERE key = 'link_secret'").fetchone()
            if row is not None:
                return bytes.fromhex(row["value"])
            secret = secrets.token_bytes(32)
            con.execute("INSERT INTO server_meta (key, value) VALUES ('link_secret', ?)", (secret.hex(),))
            return secret

    @staticmethod
    def _next_seq(con: sqlite3.Connection, user_id: str) -> int:
        con.execute("UPDATE users SET seq = seq + 1 WHERE id = ?", (user_id,))
        return int(con.execute("SELECT seq FROM users WHERE id = ?", (user_id,)).fetchone()[0])

    @staticmethod
    def _one(con: sqlite3.Connection, sql: str, *params: Any) -> dict[str, Any] | None:
        row = con.execute(sql, params).fetchone()
        return dict(row) if row is not None else None

    # ---- server ----------------------------------------------------------------

    @offloaded
    def get_passcode(self) -> PasscodeRecord | None:
        with self.db.read() as con:
            row = con.execute("SELECT salt, hash, iterations, set_at FROM passcode WHERE id = 1").fetchone()
        return PasscodeRecord.from_json(dict(row)) if row is not None else None

    @offloaded
    def set_passcode(self, record: PasscodeRecord) -> bool:
        """Store the passcode unless one is set already; True when this call set it."""
        with self.db.tx() as con:
            cursor = con.execute(
                "INSERT OR IGNORE INTO passcode (id, salt, hash, iterations, set_at) VALUES (1, ?, ?, ?, ?)",
                (record.salt, record.hash, record.iterations, record.set_at),
            )
            return cursor.rowcount == 1

    @offloaded
    def count_users(self) -> int:
        with self.db.read() as con:
            return int(con.execute("SELECT COUNT(*) FROM users").fetchone()[0])

    # ---- users and devices -----------------------------------------------------

    @offloaded
    def register(
        self, name: str, device_id: str, device_name: str, device_kind: str, token_hash: str
    ) -> tuple[dict[str, Any], dict[str, Any], bool]:
        """A new user with this device, or, for a known device id, its user with a fresh token.

        Returns (user, device, created).
        """
        now = self.now()
        with self.db.tx() as con:
            device = self._one(con, "SELECT * FROM devices WHERE id = ?", device_id)
            if device is not None:
                con.execute(
                    "UPDATE devices SET name = ?, kind = ?, token_hash = ?, last_seen = ? WHERE id = ?",
                    (device_name, device_kind, token_hash, now, device_id),
                )
                user = self._one(con, "SELECT * FROM users WHERE id = ?", device["user_id"])
                device = self._one(con, "SELECT * FROM devices WHERE id = ?", device_id)
                assert user is not None and device is not None
                return user, device, False
            user_id = "u_" + secrets.token_hex(8)
            con.execute("INSERT INTO users (id, name, created_at) VALUES (?, ?, ?)", (user_id, name, now))
            con.execute(
                "INSERT INTO devices (id, user_id, kind, name, token_hash, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (device_id, user_id, device_kind, device_name, token_hash, now, now),
            )
            user = self._one(con, "SELECT * FROM users WHERE id = ?", user_id)
            device = self._one(con, "SELECT * FROM devices WHERE id = ?", device_id)
            assert user is not None and device is not None
            return user, device, True

    @offloaded
    def resolve_token(self, token_hash: str) -> tuple[dict[str, Any], dict[str, Any]] | None:
        """(user, device) of a token hash, or None. Refreshes last_seen when it is a minute old."""
        now = self.now()
        with self.db.read() as con:
            device = self._one(con, "SELECT * FROM devices WHERE token_hash = ?", token_hash)
            if device is None:
                return None
            user = self._one(con, "SELECT * FROM users WHERE id = ?", device["user_id"])
            if user is None:
                return None
            if now - device["last_seen"] >= TOUCH_INTERVAL_MS:
                con.execute("UPDATE devices SET last_seen = ? WHERE id = ?", (now, device["id"]))
                device["last_seen"] = now
            return user, device

    @offloaded
    def user_seq(self, user_id: str) -> int:
        """The user's newest change number (0 before any change)."""
        with self.db.read() as con:
            row = con.execute("SELECT seq FROM users WHERE id = ?", (user_id,)).fetchone()
        return int(row["seq"]) if row is not None else 0

    @offloaded
    def rename_user(self, user_id: str, name: str) -> dict[str, Any]:
        with self.db.tx() as con:
            con.execute("UPDATE users SET name = ? WHERE id = ?", (name, user_id))
            user = self._one(con, "SELECT * FROM users WHERE id = ?", user_id)
        if user is None:
            raise NotFound("no such user")
        return user

    @offloaded
    def list_devices(self, user_id: str) -> list[dict[str, Any]]:
        with self.db.read() as con:
            rows = con.execute("SELECT * FROM devices WHERE user_id = ? ORDER BY created_at, rowid", (user_id,))
            return [dict(row) for row in rows]

    @offloaded
    def delete_device(self, user_id: str, device_id: str) -> bool:
        with self.db.tx() as con:
            return con.execute("DELETE FROM devices WHERE id = ? AND user_id = ?", (device_id, user_id)).rowcount == 1

    # ---- code links ------------------------------------------------------------

    def _purge_links(self, con: sqlite3.Connection, now_ms: int) -> None:
        con.execute("DELETE FROM links WHERE polled_at < ?", (now_ms - linking.LINK_TTL * 1000,))
        con.execute("DELETE FROM link_codes WHERE win < ?", (linking.window_of(now_ms / 1000) - 1,))

    def _code_for(self, con: sqlite3.Connection, link_key: str, window: int) -> str:
        """The code of a link in a window: the stored one, or a fresh one no other pending link has."""
        row = con.execute("SELECT code FROM link_codes WHERE link_id = ? AND win = ?", (link_key, window)).fetchone()
        if row is not None:
            return str(row["code"])
        for salt in range(64):
            code = linking.derive_code(self._secret, link_key, window, salt)
            try:
                con.execute("INSERT INTO link_codes (win, code, link_id) VALUES (?, ?, ?)", (window, code, link_key))
            except sqlite3.IntegrityError:
                continue  # another pending link shows this code in this window
            return code
        raise RuntimeError("no free link code")  # 64 collisions in a row among ~10^9 codes: not going to happen

    @offloaded
    def create_link(self, link_key: str, device_id: str, device_name: str, device_kind: str) -> tuple[str, float]:
        """Start a pending link; returns (current code, clock time in seconds)."""
        now = self.now()
        t = now / 1000
        window = linking.window_of(t)
        with self.db.tx() as con:
            self._purge_links(con, now)
            con.execute(
                "INSERT INTO links (id, device_id, device_name, device_kind, created_at, polled_at, sent_window) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (link_key, device_id, device_name, device_kind, now, now, window),
            )
            return self._code_for(con, link_key, window), t

    @offloaded
    def poll_link(self, link_key: str) -> LinkState | None:
        """Status of a link (None: unknown or expired); counts as a poll, so it keeps the link alive."""
        now = self.now()
        t = now / 1000
        with self.db.read() as con:  # unknown ids (no auth on this endpoint) cost one read, no write
            link = self._one(con, "SELECT * FROM links WHERE id = ?", link_key)
        if link is None or link["polled_at"] < now - linking.LINK_TTL * 1000:
            return None  # an expired row goes with the next purge
        with self.db.tx() as con:
            con.execute("UPDATE links SET polled_at = ? WHERE id = ?", (now, link_key))
            if link["claimed_by"] is not None:
                return LinkState(claimed=True, t=t)
            window = linking.window_of(t)
            return LinkState(False, self._code_for(con, link_key, window), window, link["sent_window"], t)

    @offloaded
    def mark_link_sent(self, link_key: str, window: int) -> None:
        """Remember that the device has heard the code of `window` (a later poll waits for the next change)."""
        with self.db.tx() as con:
            con.execute("UPDATE links SET sent_window = MAX(sent_window, ?) WHERE id = ?", (window, link_key))

    @offloaded
    def claim_link(self, user_id: str, code: str, caller_device_id: str) -> Claimed | None:
        """Give the device of the pending link with this code to the user; None when no link has the code.

        The code of the current window counts, and during the first GRACE seconds of a window also the code the
        link showed in the previous one. The device keeps its record if it is known (it may change users) and
        loses its old token at once; its new token is created when it collects it (deliver_link).
        """
        now = self.now()
        t = now / 1000
        window = linking.window_of(t)
        windows = [window, window - 1] if linking.in_grace(t) else [window]
        with self.db.tx() as con:
            self._purge_links(con, now)
            # Codes of the current window may not have been handed out yet (no poll since the rotation).
            pending = con.execute("SELECT id FROM links WHERE claimed_by IS NULL ORDER BY created_at, rowid").fetchall()
            for row in pending:
                self._code_for(con, row["id"], window)
            link = self._one(
                con,
                f"SELECT links.* FROM link_codes JOIN links ON links.id = link_codes.link_id "
                f"WHERE link_codes.code = ? AND link_codes.win IN ({_placeholders(windows)}) AND links.claimed_by IS NULL "
                f"ORDER BY link_codes.win DESC LIMIT 1",
                code, *windows,
            )
            if link is None:
                return None
            if link["device_id"] == caller_device_id:
                raise InvalidRequest("a device cannot link itself")
            if self._one(con, "SELECT id FROM devices WHERE id = ?", link["device_id"]) is not None:
                con.execute(
                    "UPDATE devices SET user_id = ?, name = ?, kind = ?, token_hash = NULL, last_seen = ? WHERE id = ?",
                    (user_id, link["device_name"], link["device_kind"], now, link["device_id"]),
                )
            else:
                con.execute(
                    "INSERT INTO devices (id, user_id, kind, name, token_hash, created_at, last_seen) "
                    "VALUES (?, ?, ?, ?, NULL, ?, ?)",
                    (link["device_id"], user_id, link["device_kind"], link["device_name"], now, now),
                )
            con.execute("UPDATE links SET claimed_by = ?, claimed_at = ? WHERE id = ?", (user_id, now, link["id"]))
            device = self._one(con, "SELECT * FROM devices WHERE id = ?", link["device_id"])
            assert device is not None
            return Claimed(link["id"], device)

    @offloaded
    def deliver_link(self, link_key: str, token_hash: str) -> tuple[dict[str, Any], dict[str, Any]] | None:
        """Finish a claimed link: the device gets this token and the link is gone. None if it cannot be finished."""
        now = self.now()
        with self.db.tx() as con:
            link = self._one(con, "SELECT * FROM links WHERE id = ? AND claimed_by IS NOT NULL", link_key)
            if link is None:
                return None
            con.execute("DELETE FROM links WHERE id = ?", (link_key,))
            updated = con.execute(
                "UPDATE devices SET token_hash = ?, last_seen = ? WHERE id = ? AND user_id = ?",
                (token_hash, now, link["device_id"], link["claimed_by"]),
            ).rowcount
            if not updated:
                return None  # unlinked again before it collected its token
            user = self._one(con, "SELECT * FROM users WHERE id = ?", link["claimed_by"])
            device = self._one(con, "SELECT * FROM devices WHERE id = ?", link["device_id"])
            assert user is not None and device is not None
            return user, device

    # ---- preferences -----------------------------------------------------------

    @offloaded
    def get_prefs(self, user_id: str) -> tuple[dict[str, Any], int]:
        """(prefs, updated_at); never-set prefs are all null with updated_at 0."""
        with self.db.read() as con:
            row = con.execute("SELECT data, updated_at FROM prefs WHERE user_id = ?", (user_id,)).fetchone()
        return (_prefs(row["data"]), row["updated_at"]) if row is not None else (dict(DEFAULT_PREFS), 0)

    @offloaded
    def put_prefs(self, user_id: str, changes: dict[str, Any]) -> tuple[dict[str, Any], int, int | None]:
        """Merge `changes` into the prefs; (prefs, updated_at, seq or None when nothing changed)."""
        now = self.now()
        with self.db.tx() as con:
            row = con.execute("SELECT data, updated_at FROM prefs WHERE user_id = ?", (user_id,)).fetchone()
            current = _prefs(row["data"]) if row is not None else dict(DEFAULT_PREFS)
            merged = {**current, **{k: v for k, v in changes.items() if k in PREF_KEYS}}
            if row is not None and merged == current:
                return current, row["updated_at"], None
            seq = self._next_seq(con, user_id)
            con.execute(
                "INSERT INTO prefs (user_id, data, updated_at, seq) VALUES (?, ?, ?, ?) "
                "ON CONFLICT (user_id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at, seq = excluded.seq",
                (user_id, json.dumps(merged), now, seq),
            )
            return merged, now, seq

    # ---- dialogs and messages --------------------------------------------------

    @offloaded
    def create_dialog(
        self, user_id: str, dialog_id: str, title: str, created_at: int | None
    ) -> tuple[dict[str, Any], bool, int | None]:
        """(dialog, created, seq). Creating an id the user already has returns that dialog unchanged."""
        now = self.now()
        with self.db.tx() as con:
            row = con.execute("SELECT * FROM dialogs WHERE id = ?", (dialog_id,)).fetchone()
            if row is not None:
                if row["user_id"] != user_id:
                    raise Conflict("this dialog id is already taken")
                return dialog_json(row), False, None
            seq = self._next_seq(con, user_id)
            con.execute(
                "INSERT INTO dialogs (id, user_id, title, created_at, updated_at, deleted, seq) VALUES (?, ?, ?, ?, ?, 0, ?)",
                (dialog_id, user_id, title, created_at if created_at is not None else now, now, seq),
            )
            row = con.execute("SELECT * FROM dialogs WHERE id = ?", (dialog_id,)).fetchone()
            return dialog_json(row), True, seq

    @offloaded
    def rename_dialog(self, user_id: str, dialog_id: str, title: str) -> tuple[dict[str, Any], int | None]:
        now = self.now()
        with self.db.tx() as con:
            row = con.execute("SELECT * FROM dialogs WHERE id = ? AND user_id = ?", (dialog_id, user_id)).fetchone()
            if row is None or row["deleted"]:
                raise NotFound("no such dialog")
            if row["title"] == title:
                return dialog_json(row), None
            seq = self._next_seq(con, user_id)
            con.execute("UPDATE dialogs SET title = ?, updated_at = ?, seq = ? WHERE id = ?", (title, now, seq, dialog_id))
            return dialog_json(con.execute("SELECT * FROM dialogs WHERE id = ?", (dialog_id,)).fetchone()), seq

    def _tombstone_messages(self, con: sqlite3.Connection, user_id: str, message_ids: list[str], now: int) -> int | None:
        seq = None
        for message_id in message_ids:
            seq = self._next_seq(con, user_id)
            con.execute(
                "UPDATE messages SET content = '', thinking = '', sources = '[]', attachments = '[]', error = '', "
                "status = CASE status WHEN 'streaming' THEN 'done' ELSE status END, "
                "deleted = 1, updated_at = ?, seq = ? WHERE id = ?",
                (now, seq, message_id),
            )
        return seq

    def _release_attachments(self, con: sqlite3.Connection, message_ids: list[str]) -> list[tuple[str, str]]:
        """Detach the messages' attachments; delete (and return) those no message uses any more."""
        if not message_ids:
            return []
        marks = _placeholders(message_ids)
        used = [r[0] for r in con.execute(
            f"SELECT DISTINCT attachment_id FROM message_attachments WHERE message_id IN ({marks})", message_ids
        )]
        con.execute(f"DELETE FROM message_attachments WHERE message_id IN ({marks})", message_ids)
        orphans: list[tuple[str, str]] = []
        for attachment_id in used:
            if con.execute("SELECT 1 FROM message_attachments WHERE attachment_id = ? LIMIT 1", (attachment_id,)).fetchone():
                continue
            row = con.execute("SELECT user_id FROM attachments WHERE id = ?", (attachment_id,)).fetchone()
            if row is not None:
                con.execute("DELETE FROM attachments WHERE id = ?", (attachment_id,))
                orphans.append((row["user_id"], attachment_id))
        return orphans

    @offloaded
    def delete_dialog(self, user_id: str, dialog_id: str) -> Deleted:
        """The dialog and all its messages become tombstones."""
        now = self.now()
        with self.db.tx() as con:
            row = con.execute("SELECT * FROM dialogs WHERE id = ? AND user_id = ?", (dialog_id, user_id)).fetchone()
            if row is None:
                raise NotFound("no such dialog")
            if row["deleted"]:
                return Deleted(None)
            live = [r["id"] for r in con.execute(
                "SELECT id FROM messages WHERE dialog_id = ? AND deleted = 0 ORDER BY rowid", (dialog_id,)
            )]
            self._tombstone_messages(con, user_id, live, now)
            seq = self._next_seq(con, user_id)
            con.execute(
                "UPDATE dialogs SET title = '', deleted = 1, updated_at = ?, seq = ? WHERE id = ?", (now, seq, dialog_id)
            )
            return Deleted(seq, live, self._release_attachments(con, live))

    @offloaded
    def delete_message(self, user_id: str, message_id: str) -> Deleted:
        now = self.now()
        with self.db.tx() as con:
            row = con.execute("SELECT deleted FROM messages WHERE id = ? AND user_id = ?", (message_id, user_id)).fetchone()
            if row is None:
                raise NotFound("no such message")
            if row["deleted"]:
                return Deleted(None)
            seq = self._tombstone_messages(con, user_id, [message_id], now)
            return Deleted(seq, [message_id], self._release_attachments(con, [message_id]))

    @offloaded
    def get_message(self, user_id: str, message_id: str) -> dict[str, Any] | None:
        with self.db.read() as con:
            row = con.execute("SELECT * FROM messages WHERE id = ? AND user_id = ?", (message_id, user_id)).fetchone()
        return message_json(row) if row is not None else None

    @offloaded
    def sync(self, user_id: str, since: int, limit: int) -> dict[str, Any]:
        """Objects changed after `since`, in seq order, at most `limit` in all (prefs count as one object)."""
        with self.db.read() as con:
            dialogs = [dialog_json(r) for r in con.execute(
                "SELECT * FROM dialogs WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?", (user_id, since, limit + 1)
            )]
            messages = [message_json(r) for r in con.execute(
                "SELECT * FROM messages WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?", (user_id, since, limit + 1)
            )]
            prefs = con.execute("SELECT data, seq FROM prefs WHERE user_id = ? AND seq > ?", (user_id, since)).fetchone()
        items: list[tuple[int, str, Any]] = [(d["seq"], "dialogs", d) for d in dialogs]
        items += [(m["seq"], "messages", m) for m in messages]
        if prefs is not None:
            items.append((prefs["seq"], "prefs", _prefs(prefs["data"])))
        items.sort(key=lambda item: item[0])
        result: dict[str, Any] = {"dialogs": [], "messages": [], "prefs": None, "seq": since, "more": len(items) > limit}
        for seq, kind, obj in items[:limit]:
            if kind == "prefs":
                result["prefs"] = obj
            else:
                result[kind].append(obj)
            result["seq"] = seq
        return result

    # ---- chat ------------------------------------------------------------------

    def _own_attachments(self, con: sqlite3.Connection, user_id: str, ids: list[str]) -> list[dict[str, Any]]:
        if not ids:
            return []
        rows = {r["id"]: dict(r) for r in con.execute(
            f"SELECT * FROM attachments WHERE user_id = ? AND id IN ({_placeholders(ids)})", (user_id, *ids)
        )}
        missing = [i for i in ids if i not in rows]
        if missing:
            raise InvalidRequest(f"unknown attachment {missing[0]!r}")
        return [rows[i] for i in ids]

    @offloaded
    def begin_answer(
        self,
        user_id: str,
        dialog_id: str,
        message_id: str,
        content: str,
        attachment_ids: list[str],
        created_at: int | None,
        answer_id: str,
        model: str,
    ) -> Begun:
        """Save the question (idempotent by id; the dialog is created if needed) and the streaming answer.

        A request repeated with the same answer id gets the existing answer back (`existing`), unchanged.
        """
        now = self.now()
        with self.db.tx() as con:
            attachments = self._own_attachments(con, user_id, attachment_ids)
            dialog = con.execute("SELECT * FROM dialogs WHERE id = ?", (dialog_id,)).fetchone()
            if dialog is not None and dialog["user_id"] != user_id:
                raise Conflict("this dialog id is already taken")
            if dialog is not None and dialog["deleted"]:
                raise NotFound("the dialog was deleted")
            question = con.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
            if question is not None:
                if (question["user_id"], question["dialog_id"], question["role"]) != (user_id, dialog_id, "user"):
                    raise Conflict("this message id is already taken")
                if question["deleted"]:
                    raise NotFound("the message was deleted")
            answer = con.execute("SELECT * FROM messages WHERE id = ?", (answer_id,)).fetchone()
            if answer is not None:
                if (answer["user_id"], answer["dialog_id"], answer["role"]) != (user_id, dialog_id, "assistant") \
                        or question is None:
                    raise Conflict("this answer id is already taken")
                if answer["deleted"]:
                    raise NotFound("the answer was deleted")
                return Begun(message_json(question), message_json(answer), existing=True, seq=None)

            asked_at = created_at if created_at is not None else now
            if dialog is None:  # its real seq comes with the update below, after the messages
                con.execute(
                    "INSERT INTO dialogs (id, user_id, title, created_at, updated_at, deleted, seq) VALUES (?, ?, '', ?, ?, 0, 0)",
                    (dialog_id, user_id, asked_at, now),
                )
                dialog = con.execute("SELECT * FROM dialogs WHERE id = ?", (dialog_id,)).fetchone()
            if question is None:
                seq = self._next_seq(con, user_id)
                con.execute(
                    "INSERT INTO messages (id, dialog_id, user_id, role, content, attachments, status, created_at, updated_at, seq) "
                    "VALUES (?, ?, ?, 'user', ?, ?, 'done', ?, ?, ?)",
                    (message_id, dialog_id, user_id, content, json.dumps([attachment_ref(a) for a in attachments]),
                     asked_at, now, seq),
                )
                con.executemany(
                    "INSERT OR IGNORE INTO message_attachments (message_id, attachment_id) VALUES (?, ?)",
                    [(message_id, a["id"]) for a in attachments],
                )
                question = con.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
            seq = self._next_seq(con, user_id)
            # Never before its question, even when the asking device's clock runs ahead of the server's.
            answered_at = max(now, question["created_at"] + 1)
            con.execute(
                "INSERT INTO messages (id, dialog_id, user_id, role, model, status, created_at, updated_at, seq) "
                "VALUES (?, ?, ?, 'assistant', ?, 'streaming', ?, ?, ?)",
                (answer_id, dialog_id, user_id, model, answered_at, now, seq),
            )
            seq = self._next_seq(con, user_id)
            title = dialog["title"] or auto_title(question["content"])
            con.execute("UPDATE dialogs SET title = ?, updated_at = ?, seq = ? WHERE id = ?", (title, now, seq, dialog_id))
            answer = con.execute("SELECT * FROM messages WHERE id = ?", (answer_id,)).fetchone()
            return Begun(message_json(question), message_json(answer), existing=False, seq=seq)

    @offloaded
    def history(self, dialog_id: str, exclude_id: str, limit: int) -> list[dict[str, Any]]:
        """The newest `limit` usable messages of a dialog, oldest first: not deleted, done or error, with text or images."""
        with self.db.read() as con:
            rows = con.execute(
                "SELECT role, content, attachments FROM messages WHERE dialog_id = ? AND id != ? AND deleted = 0 "
                "AND status IN ('done', 'error') AND (content != '' OR attachments != '[]') ORDER BY rowid DESC LIMIT ?",
                (dialog_id, exclude_id, limit),
            ).fetchall()
        return [
            {"role": r["role"], "content": r["content"], "attachments": json.loads(r["attachments"])}
            for r in reversed(rows)
        ]

    @offloaded
    def save_partial(self, answer_id: str, content: str, thinking: str) -> tuple[str, int] | None:
        """Save a streaming answer's text so far; (user_id, seq), or None when it is no longer streaming."""
        now = self.now()
        with self.db.tx() as con:
            row = con.execute(
                "SELECT user_id FROM messages WHERE id = ? AND deleted = 0 AND status = 'streaming'", (answer_id,)
            ).fetchone()
            if row is None:
                return None
            seq = self._next_seq(con, row["user_id"])
            con.execute(
                "UPDATE messages SET content = ?, thinking = ?, updated_at = ?, seq = ? WHERE id = ?",
                (content, thinking, now, seq, answer_id),
            )
            return row["user_id"], seq

    @offloaded
    def finish_answer(
        self, answer_id: str, content: str, thinking: str, sources: list[dict[str, str]], error: str = ""
    ) -> tuple[dict[str, Any], int | None] | None:
        """Final save (status done, or error with its code). A deleted answer stays a tombstone: (tombstone, None)."""
        now = self.now()
        with self.db.tx() as con:
            row = con.execute("SELECT * FROM messages WHERE id = ?", (answer_id,)).fetchone()
            if row is None:
                return None
            if row["deleted"]:
                return message_json(row), None
            seq = self._next_seq(con, row["user_id"])
            con.execute(
                "UPDATE messages SET content = ?, thinking = ?, sources = ?, status = ?, error = ?, updated_at = ?, seq = ? "
                "WHERE id = ?",
                (content, thinking, json.dumps(sources), "error" if error else "done", error, now, seq, answer_id),
            )
            return message_json(con.execute("SELECT * FROM messages WHERE id = ?", (answer_id,)).fetchone()), seq

    def fail_interrupted_answers(self) -> int:
        """At startup: answers left `streaming` by a previous process can never finish; mark them as errors."""
        now = self.now()
        with self.db.tx() as con:
            rows = con.execute("SELECT id, user_id FROM messages WHERE status = 'streaming' AND deleted = 0").fetchall()
            for row in rows:
                seq = self._next_seq(con, row["user_id"])
                con.execute(
                    "UPDATE messages SET status = 'error', error = 'internal_error', updated_at = ?, seq = ? WHERE id = ?",
                    (now, seq, row["id"]),
                )
        if rows:
            log.warning("Marked %d answer(s) interrupted by a restart as failed", len(rows))
        return len(rows)

    # ---- attachments -----------------------------------------------------------

    @offloaded
    def add_attachment(
        self, user_id: str, attachment_id: str, mime: str, width: int, height: int, size: int
    ) -> dict[str, Any]:
        now = self.now()
        with self.db.tx() as con:
            con.execute(
                "INSERT INTO attachments (id, user_id, mime, width, height, size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (attachment_id, user_id, mime, width, height, size, now),
            )
            row = self._one(con, "SELECT * FROM attachments WHERE id = ?", attachment_id)
        assert row is not None
        return row

    @offloaded
    def get_attachment(self, user_id: str, attachment_id: str) -> dict[str, Any] | None:
        with self.db.read() as con:
            return self._one(con, "SELECT * FROM attachments WHERE id = ? AND user_id = ?", attachment_id, user_id)

    # ---- the pre-v2 state file -------------------------------------------------

    def migrate_legacy_state(self, path: Path) -> bool:
        """Import DATA_DIR/state.json once: its passcode becomes the server passcode (unless one is set) and its
        tokens watch devices of a user named "Owner". The file is renamed to state.json.migrated afterwards."""
        path = Path(path)
        if not path.exists():
            return False
        with self.db.read() as con:
            done = con.execute("SELECT 1 FROM server_meta WHERE key = 'legacy_state_migrated'").fetchone()
        if done is None:
            try:
                state = read_legacy_state(path)
            except (OSError, ValueError, KeyError, TypeError) as exc:
                log.error("Cannot read the old state file %s (%s); it was not migrated", path, exc)
                return False
            now = self.now()
            with self.db.tx() as con:
                if state.passcode is not None:
                    p = state.passcode
                    con.execute(
                        "INSERT OR IGNORE INTO passcode (id, salt, hash, iterations, set_at) VALUES (1, ?, ?, ?, ?)",
                        (p.salt, p.hash, p.iterations, p.set_at),
                    )
                if state.tokens:
                    user_id = "u_" + secrets.token_hex(8)
                    con.execute("INSERT INTO users (id, name, created_at) VALUES (?, ?, ?)", (user_id, LEGACY_USER_NAME, now))
                    for token in state.tokens:
                        device_id = token.device_id or "legacy-" + secrets.token_hex(8)
                        taken = con.execute(
                            "SELECT 1 FROM devices WHERE id = ? OR token_hash = ?", (device_id, token.token_hash)
                        ).fetchone()
                        if taken:
                            continue
                        created = _iso_to_ms(token.created_at, now)
                        con.execute(
                            "INSERT INTO devices (id, user_id, kind, name, token_hash, created_at, last_seen) "
                            "VALUES (?, ?, 'watch', ?, ?, ?, ?)",
                            (device_id, user_id, token.device_name, token.token_hash, created, created),
                        )
                con.execute("INSERT INTO server_meta (key, value) VALUES ('legacy_state_migrated', ?)", (str(now),))
            log.info("Migrated %s: passcode=%s, %d watch token(s)", path, state.passcode is not None, len(state.tokens))
        try:
            path.rename(path.with_name(path.name + ".migrated"))
        except OSError as exc:
            log.warning("Could not rename %s after migrating it: %s", path, exc)
        return done is None
