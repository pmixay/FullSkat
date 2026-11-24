"""The SQLite store: schema and migrations, and the one-time import of the pre-v2 state.json."""

import json
from datetime import datetime, timezone
import sqlite3
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.auth import hash_passcode, token_hash
from fullskat_server.config import Settings
from fullskat_server.db import SCHEMA_VERSION, Database
from fullskat_server.store import Store
from helpers import REGISTER, FakeLLM, bearer, chat_body, parse_sse


def test_schema_is_created_with_wal_and_foreign_keys(tmp_path: Path) -> None:
    store = Store(tmp_path / "fullskat.db")
    assert store.db.schema_version == SCHEMA_VERSION == 1
    with store.db.read() as con:
        assert con.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        assert con.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    assert {"schema_version", "users", "devices", "passcode", "links", "link_codes", "dialogs", "messages",
            "attachments", "message_attachments", "prefs", "server_meta"} <= tables
    secret = store._secret
    store.close()
    reopened = Store(tmp_path / "fullskat.db")  # migrations run once; the link secret is kept
    assert reopened.db.schema_version == 1 and reopened._secret == secret
    with reopened.db.read() as con:
        assert con.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0] == 1
    reopened.close()


def test_a_database_from_a_newer_server_is_refused(tmp_path: Path) -> None:
    Store(tmp_path / "fullskat.db").close()
    con = sqlite3.connect(tmp_path / "fullskat.db")
    con.execute("INSERT INTO schema_version (version, applied_at) VALUES (99, 'later')")
    con.commit()
    con.close()
    with pytest.raises(RuntimeError, match="newer"):
        Database(tmp_path / "fullskat.db")


def write_state(data_dir: Path, passcode: str = "2468", tokens: dict[str, str] | None = None) -> Path:
    tokens = {"old-watch-token": "watch-1"} if tokens is None else tokens
    path = data_dir / "state.json"
    path.write_text(json.dumps({
        "passcode": hash_passcode(passcode, iterations=1000).to_json(),
        "tokens": {
            token_hash(token): {"device_id": device, "device_name": f"Watch {device}", "created_at": "2026-10-01T10:00:00+00:00"}
            for token, device in tokens.items()
        },
    }))
    return path


async def test_state_json_is_migrated_once(tmp_path: Path) -> None:
    path = write_state(tmp_path, tokens={"old-watch-token": "watch-1", "second-token": "watch-2"})
    settings = Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k")
    app = create_app(settings)
    app.state.llm = FakeLLM()
    assert not path.exists() and (tmp_path / "state.json.migrated").exists()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        me = await client.get("/v1/me", headers=bearer("old-watch-token"))
        assert me.status_code == 200
        assert me.json()["user"]["name"] == "Owner"
        assert me.json()["device"] == {
            "id": "watch-1", "kind": "watch", "name": "Watch watch-1",
            "created_at": int(datetime(2026, 10, 1, 10, tzinfo=timezone.utc).timestamp() * 1000), "last_seen": me.json()["device"]["last_seen"], "current": True,
        }
        devices = (await client.get("/v1/devices", headers=bearer("second-token"))).json()["devices"]
        assert [d["id"] for d in devices] == ["watch-1", "watch-2"]

        # The old watch keeps chatting the old, stateless way.
        legacy = await client.post("/v1/chat", json={"messages": [{"role": "user", "content": "hi"}]},
                                   headers=bearer("old-watch-token"))
        assert parse_sse(legacy.text)[-1][0] == "done"
        assert "smartwatch" in app.state.llm.requests[-1][0][0]["content"]
        # The old passcode is the server passcode now.
        assert (await client.post("/v1/register", json={**REGISTER, "passcode": "1234"})).status_code == 401
        joined = await client.post("/v1/register", json={**REGISTER, "passcode": "2468"})
        assert joined.status_code == 201 and joined.json()["first_setup"] is False
        assert (await client.get("/healthz")).json() == {"ok": True, "users": 2}
    app.state.store.close()

    # A state.json that shows up again is not imported twice.
    write_state(tmp_path, passcode="0000")
    again = create_app(settings)
    again.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=again), base_url="http://test") as client:
        assert (await client.get("/healthz")).json()["users"] == 2
        assert (await client.post("/v1/register", json={**REGISTER, "device_id": "p9", "passcode": "2468"})).status_code == 201
    again.state.store.close()


async def test_a_passcode_without_tokens_creates_no_user(tmp_path: Path) -> None:
    write_state(tmp_path, tokens={})
    app = create_app(Settings(_env_file=None, data_dir=tmp_path))
    app.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/healthz")).json()["users"] == 0
        first = await client.post("/v1/register", json={**REGISTER, "passcode": "2468"})
        assert first.status_code == 201 and first.json()["first_setup"] is False
    app.state.store.close()


async def test_an_unreadable_state_file_is_left_alone(tmp_path: Path) -> None:
    (tmp_path / "state.json").write_text("{broken")
    app = create_app(Settings(_env_file=None, data_dir=tmp_path))
    assert (tmp_path / "state.json").exists()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/healthz")).json()["users"] == 0
    app.state.store.close()


async def test_data_survives_a_restart(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k")
    app = create_app(settings)
    app.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = (await client.post("/v1/register", json=REGISTER)).json()["token"]
        await client.post("/v1/chat", json=chat_body(), headers=bearer(token))
    app.state.store.close()
    restarted = create_app(settings)
    async with AsyncClient(transport=ASGITransport(app=restarted), base_url="http://test") as client:
        synced = (await client.get("/v1/sync", headers=bearer(token))).json()
        assert [m["content"] for m in synced["messages"]] == ["Привет", "Hello world"]
    restarted.state.store.close()
