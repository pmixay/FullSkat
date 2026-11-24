"""SQLite plumbing: one connection, one worker thread, WAL, foreign keys and schema migrations.

Every query runs on a single dedicated thread (`Database.run`), so database work never blocks the event
loop and all access is serialized: a transaction can read, decide and write without another request
slipping in between. Methods decorated with `offloaded` are awaitable and run there; plain methods are
for startup code that runs before the event loop (the same lock keeps them safe).
"""

from __future__ import annotations

import asyncio
import functools
import logging
import os
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Awaitable, Callable, Iterator, ParamSpec, TypeVar

log = logging.getLogger(__name__)

P = ParamSpec("P")
T = TypeVar("T")

# MIGRATIONS[n] takes the schema from version n to n + 1. Append, never edit a released step.
MIGRATIONS: list[list[str]] = [
    [
        "CREATE TABLE server_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        # The invitation passcode (PBKDF2-HMAC-SHA256, see auth.py); one row at most.
        """CREATE TABLE passcode (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            salt TEXT NOT NULL, hash TEXT NOT NULL, iterations INTEGER NOT NULL, set_at TEXT NOT NULL)""",
        # `seq` is the user's sequence counter: every change of their dialogs, messages or prefs takes the next value.
        """CREATE TABLE users (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0)""",
        # Only the SHA-256 of a token is stored; NULL while a linked device has not collected its token yet.
        """CREATE TABLE devices (
            id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', token_hash TEXT UNIQUE,
            created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)""",
        "CREATE INDEX devices_user ON devices(user_id)",
        # Pending code links. `id` is the SHA-256 of the link_id secret the new device holds.
        """CREATE TABLE links (
            id TEXT PRIMARY KEY, device_id TEXT NOT NULL, device_name TEXT NOT NULL, device_kind TEXT NOT NULL,
            created_at INTEGER NOT NULL, polled_at INTEGER NOT NULL, sent_window INTEGER NOT NULL DEFAULT -1,
            claimed_by TEXT REFERENCES users(id) ON DELETE CASCADE, claimed_at INTEGER)""",
        # The code each link shows in each 30-second window; the primary key keeps codes unique per window.
        """CREATE TABLE link_codes (
            win INTEGER NOT NULL, code TEXT NOT NULL,
            link_id TEXT NOT NULL REFERENCES links(id) ON DELETE CASCADE,
            PRIMARY KEY (win, code), UNIQUE (link_id, win))""",
        """CREATE TABLE dialogs (
            id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            title TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL)""",
        "CREATE INDEX dialogs_user_seq ON dialogs(user_id, seq)",
        # Messages are ordered by rowid (insertion order) for history; `attachments` and `sources` are JSON.
        """CREATE TABLE messages (
            id TEXT PRIMARY KEY, dialog_id TEXT NOT NULL REFERENCES dialogs(id) ON DELETE CASCADE,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
            content TEXT NOT NULL DEFAULT '', thinking TEXT NOT NULL DEFAULT '',
            sources TEXT NOT NULL DEFAULT '[]', attachments TEXT NOT NULL DEFAULT '[]',
            model TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'done' CHECK (status IN ('done', 'streaming', 'error')),
            error TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL)""",
        "CREATE INDEX messages_user_seq ON messages(user_id, seq)",
        "CREATE INDEX messages_dialog ON messages(dialog_id)",
        """CREATE TABLE attachments (
            id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            mime TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, size INTEGER NOT NULL,
            created_at INTEGER NOT NULL)""",
        "CREATE INDEX attachments_user ON attachments(user_id)",
        # Which messages use which attachment; an attachment no message uses any more is deleted with its files.
        """CREATE TABLE message_attachments (
            message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            attachment_id TEXT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
            PRIMARY KEY (message_id, attachment_id))""",
        "CREATE INDEX message_attachments_attachment ON message_attachments(attachment_id)",
        """CREATE TABLE prefs (
            user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            data TEXT NOT NULL, updated_at INTEGER NOT NULL, seq INTEGER NOT NULL)""",
    ],
]

SCHEMA_VERSION = len(MIGRATIONS)


class Database:
    """A single SQLite connection used from one worker thread (plus startup code, under the same lock)."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._con = sqlite3.connect(self.path, isolation_level=None, check_same_thread=False, timeout=30.0)
        self._con.row_factory = sqlite3.Row
        self._con.execute("PRAGMA journal_mode=WAL")
        self._con.execute("PRAGMA synchronous=NORMAL")
        self._con.execute("PRAGMA foreign_keys=ON")
        self._executor: ThreadPoolExecutor | None = ThreadPoolExecutor(max_workers=1, thread_name_prefix="fullskat-db")
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass
        self._migrate()

    # ---- execution ---------------------------------------------------------

    async def run(self, fn: Callable[P, T], *args: P.args, **kwargs: P.kwargs) -> T:
        """Run a blocking function on the database thread and await its result."""
        if self._executor is None:
            raise RuntimeError("database is closed")
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._executor, functools.partial(fn, *args, **kwargs))

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Connection]:
        """A write transaction (BEGIN IMMEDIATE ... COMMIT, ROLLBACK on any exception)."""
        with self._lock:
            self._con.execute("BEGIN IMMEDIATE")
            try:
                yield self._con
            except BaseException:
                self._con.execute("ROLLBACK")
                raise
            self._con.execute("COMMIT")

    @contextmanager
    def read(self) -> Iterator[sqlite3.Connection]:
        """The connection for reads (autocommit; consistent because all access is serialized)."""
        with self._lock:
            yield self._con

    def close(self) -> None:
        with self._lock:
            if self._executor is not None:
                self._executor.shutdown(wait=True)
                self._executor = None
            try:
                self._con.close()
            except sqlite3.Error:
                pass

    # ---- schema ------------------------------------------------------------

    @property
    def schema_version(self) -> int:
        with self.read() as con:
            row = con.execute("SELECT MAX(version) FROM schema_version").fetchone()
            return int(row[0] or 0)

    def _migrate(self) -> None:
        with self.tx() as con:
            con.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL, applied_at TEXT NOT NULL)")
            row = con.execute("SELECT MAX(version) FROM schema_version").fetchone()
            current = int(row[0] or 0)
            if current > SCHEMA_VERSION:
                raise RuntimeError(
                    f"{self.path} has schema version {current}, newer than this server ({SCHEMA_VERSION}); upgrade the server"
                )
            for version in range(current, SCHEMA_VERSION):
                for statement in MIGRATIONS[version]:
                    con.execute(statement)
                con.execute(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, datetime('now'))", (version + 1,)
                )
                log.info("Database %s migrated to schema version %d", self.path, version + 1)


def offloaded(method: Callable[..., T]) -> Callable[..., Awaitable[T]]:
    """Make a blocking method of an object with a `db: Database` awaitable: it runs on the database thread."""

    @functools.wraps(method)
    async def wrapper(self: Any, *args: Any, **kwargs: Any) -> T:
        return await self.db.run(method, self, *args, **kwargs)

    wrapper.blocking = method  # type: ignore[attr-defined]
    return wrapper
