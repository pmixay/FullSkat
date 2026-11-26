"""Linking a device with a rotating code: start, long-poll status, claim."""

import asyncio
import time

import pytest
from fastapi import FastAPI
from httpx import AsyncClient

from fullskat_server import linking
from fullskat_server.ratelimit import PairLimiter
from helpers import FakeClock, bearer, register

WATCH = {"device_id": "watch-1", "device_name": "Galaxy Watch7", "device_kind": "watch"}
WINDOW = 60_000_000  # a code window far from any real one; its start is WINDOW * 30


def at(window: int, offset: float) -> float:
    return window * linking.PERIOD + offset


async def start(client: AsyncClient, **overrides: str) -> dict:
    response = await client.post("/v1/link/start", json={**WATCH, **overrides})
    assert response.status_code == 200, response.text
    return response.json()


async def status(client: AsyncClient, link: dict, wait: int = 0) -> tuple[int, dict]:
    response = await client.get("/v1/link/status", params={"link_id": link["link_id"], "wait": wait})
    return response.status_code, response.json()


async def claim(client: AsyncClient, token: str, code: str) -> tuple[int, dict]:
    response = await client.post("/v1/link/claim", json={"code": code}, headers=bearer(token))
    return response.status_code, response.json()


# ---- codes ----------------------------------------------------------------------


def test_codes_use_the_alphabet_and_are_deterministic() -> None:
    secret = b"s" * 32
    code = linking.derive_code(secret, "link", 7)
    assert len(code) == 6 and all(ch in linking.ALPHABET for ch in code)
    assert set(linking.ALPHABET).isdisjoint("01IO")
    assert linking.derive_code(secret, "link", 7) == code
    assert linking.derive_code(secret, "link", 8) != code  # rotates with the window
    assert linking.derive_code(secret, "link", 7, salt=1) != code
    assert linking.derive_code(b"t" * 32, "link", 7) != code
    assert linking.normalize_code(" k7q-2zp ") == "K7Q2ZP"
    assert linking.is_code("K7Q2ZP") and not linking.is_code("K7Q2Z0") and not linking.is_code("K7Q2Z")


def test_window_math() -> None:
    assert linking.window_of(at(5, 0)) == 5 and linking.window_of(at(5, 29.99)) == 5
    assert linking.expires_in(at(5, 7)) == 23 and linking.expires_in(at(5, 0)) == 30
    assert linking.expires_in(at(5, 29.9)) == 1
    assert linking.in_grace(at(5, 4.9)) and not linking.in_grace(at(5, 5.0))


# ---- the flow --------------------------------------------------------------------


async def test_link_flow(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 7))
    phone = await register(client)
    link = await start(client)
    assert link["period"] == 30 and link["expires_in"] == 23
    assert linking.is_code(link["code"]) and len(link["link_id"]) >= 43

    # Nothing changed yet: wait=0 answers at once with the same code.
    assert await status(client, link) == (200, {"status": "pending", "code": link["code"], "expires_in": 23, "period": 30})

    code, device = await claim(client, phone["token"], f" {link['code'][:3].lower()}-{link['code'][3:]} ")
    assert code == 200
    assert device["device"]["id"] == "watch-1" and device["device"]["kind"] == "watch"
    assert device["device"]["name"] == "Galaxy Watch7" and device["device"]["current"] is False

    code, linked = await status(client, link)
    assert code == 200 and linked["status"] == "linked"
    assert linked["user"] == phone["user"]
    assert linked["device"]["id"] == "watch-1" and linked["device"]["current"] is True
    assert linked["server_name"] == "FullSkat" and linked["model"] == "claude-haiku-5-5" and "model_efforts" in linked
    me = await client.get("/v1/me", headers=bearer(linked["token"]))
    assert me.status_code == 200 and me.json()["user"]["id"] == phone["user"]["id"]

    # The token is delivered once; then the link is gone.
    assert await status(client, link) == (404, {"error": "link_expired", "message": "the link expired; start over"})
    assert (await claim(client, phone["token"], link["code"]))[0] == 404


async def test_status_long_poll_returns_the_new_code_when_the_window_rotates(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 29.7))
    link = await start(client)
    began = time.monotonic()
    code, pending = await status(client, link, wait=10)
    assert code == 200 and pending["status"] == "pending"
    assert pending["code"] != link["code"]
    assert pending["expires_in"] in (29, 30)
    assert time.monotonic() - began < 3  # woke at the rotation, not at the end of the wait


async def test_status_answers_at_once_when_the_device_missed_a_rotation(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 10))
    link = await start(client)
    clock.set(at(WINDOW + 1, 12))  # the device was away over a rotation
    began = time.monotonic()
    code, pending = await status(client, link, wait=25)
    assert code == 200 and pending["code"] != link["code"] and pending["expires_in"] == 18
    assert time.monotonic() - began < 2


async def test_status_wait_runs_out_with_the_same_code(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 1))
    link = await start(client)
    began = time.monotonic()
    code, pending = await status(client, link, wait=1)
    assert code == 200 and pending["code"] == link["code"]
    assert 0.9 <= time.monotonic() - began < 3


async def test_long_poll_wakes_up_when_the_code_is_claimed(client: AsyncClient, clock: FakeClock, app: FastAPI) -> None:
    clock.set(at(WINDOW, 2))
    phone = await register(client)
    link = await start(client)
    began = time.monotonic()
    waiting = asyncio.create_task(status(client, link, wait=25))
    for _ in range(200):  # until the poll is parked
        if app.state.link_waiters.count(linking_key(link)) or waiting.done():
            break
        await asyncio.sleep(0.01)
    assert not waiting.done() and app.state.link_waiters.count(linking_key(link)) == 1
    assert (await claim(client, phone["token"], link["code"]))[0] == 200
    code, linked = await asyncio.wait_for(waiting, 5)
    assert code == 200 and linked["status"] == "linked" and "token" in linked
    assert time.monotonic() - began < 3


async def test_previous_code_is_accepted_during_the_grace_window(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 20))
    phone = await register(client)
    early = await start(client, device_id="watch-a")
    late = await start(client, device_id="watch-b")

    clock.set(at(WINDOW + 1, 3))  # 3 s after the rotation: the old code still counts
    assert (await claim(client, phone["token"], early["code"]))[0] == 200

    clock.set(at(WINDOW + 1, 6))  # 6 s after: only the current one
    assert (await claim(client, phone["token"], late["code"]))[0] == 404
    code, pending = await status(client, late)
    assert pending["code"] != late["code"]
    assert (await claim(client, phone["token"], pending["code"]))[0] == 200


async def test_a_code_never_seen_cannot_be_claimed_from_the_previous_window(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW + 1, 1))  # started in the grace period of a new window
    phone = await register(client)
    link = await start(client)
    from fullskat_server.linking import derive_code

    secret = client._transport.app.state.store._secret  # type: ignore[attr-defined]
    previous = derive_code(secret, linking_key(link), WINDOW)
    assert (await claim(client, phone["token"], previous))[0] == 404


def linking_key(link: dict) -> str:
    from fullskat_server.auth import token_hash

    return token_hash(link["link_id"])


async def test_links_expire_after_ten_minutes_without_a_poll(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 1))
    phone = await register(client)
    link = await start(client)
    clock.advance(9 * 60)
    assert (await status(client, link))[0] == 200  # a poll keeps it alive
    clock.advance(9 * 60)
    assert (await status(client, link))[0] == 200
    clock.advance(10 * 60 + 1)
    assert (await status(client, link))[0] == 404
    code, current = await claim(client, phone["token"], link["code"])
    assert code == 404 and current["error"] == "invalid_code"
    assert (await client.get("/v1/link/status")).status_code == 404
    assert (await client.get("/v1/link/status", params={"link_id": "made-up"})).status_code == 404


async def test_claim_attempts_lock_out_after_consecutive_failures(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 1))
    phone = await register(client)
    link = await start(client)
    wrong = "ZZZZZZ" if link["code"] != "ZZZZZZ" else "YYYYYY"
    for _ in range(4):
        assert await claim(client, phone["token"], wrong) == (404, {"error": "invalid_code", "message": "wrong or expired code"})
    assert (await claim(client, phone["token"], "not a code"))[0] == 404  # malformed counts as wrong
    code, locked = await claim(client, phone["token"], link["code"])
    assert code == 429 and locked["error"] == "locked"
    # Another user is not affected.
    other = await register(client, device_id="phone-2", name="Аня")
    assert (await claim(client, other["token"], link["code"]))[0] == 200


async def test_claim_attempts_per_minute(client: AsyncClient, app: FastAPI) -> None:
    app.state.claim_limiter = PairLimiter(per_minute=3, lock_failures=100, lock_minutes=5)
    phone = await register(client)
    for _ in range(3):
        assert (await claim(client, phone["token"], "ZZZZZZ"))[0] == 404
    code, limited = await claim(client, phone["token"], "ZZZZZZ")
    assert code == 429 and limited["error"] == "rate_limited"


async def test_link_start_is_limited_per_ip(client: AsyncClient) -> None:
    for i in range(20):
        assert (await client.post("/v1/link/start", json={**WATCH, "device_id": f"w{i}"})).status_code == 200
    limited = await client.post("/v1/link/start", json=WATCH)
    assert limited.status_code == 429 and limited.json()["error"] == "rate_limited"


async def test_pending_links_never_share_a_code(client: AsyncClient, clock: FakeClock, monkeypatch: pytest.MonkeyPatch) -> None:
    real = linking.derive_code
    monkeypatch.setattr(linking, "derive_code", lambda secret, key, window, salt=0: "AAAAAA" if salt == 0 else real(secret, key, window, salt))
    clock.set(at(WINDOW, 1))
    phone = await register(client)
    first, second = await start(client, device_id="w1"), await start(client, device_id="w2")
    assert first["code"] == "AAAAAA" and second["code"] != "AAAAAA"
    # Each code links its own device.
    assert (await claim(client, phone["token"], second["code"]))[1]["device"]["id"] == "w2"
    assert (await claim(client, phone["token"], "AAAAAA"))[1]["device"]["id"] == "w1"


async def test_codes_stay_stable_within_a_window(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 1))
    link = await start(client)
    clock.set(at(WINDOW, 25))
    assert (await status(client, link))[1]["code"] == link["code"]


async def test_relinking_a_known_device_keeps_its_record_and_revokes_the_old_token(client: AsyncClient, clock: FakeClock) -> None:
    clock.set(at(WINDOW, 1))
    phone = await register(client)
    first = await start(client)
    await claim(client, phone["token"], first["code"])
    old = (await status(client, first))[1]

    clock.advance(120)
    second = await start(client, device_name="Watch (renamed)")
    assert (await claim(client, phone["token"], second["code"]))[0] == 200
    assert (await client.get("/v1/me", headers=bearer(old["token"]))).status_code == 401  # at once, on claim
    new = (await status(client, second))[1]
    assert new["device"]["created_at"] == old["device"]["created_at"]
    assert new["device"]["name"] == "Watch (renamed)"
    devices = (await client.get("/v1/devices", headers=bearer(new["token"]))).json()["devices"]
    assert [d["id"] for d in devices] == ["phone-1", "watch-1"]


async def test_a_device_cannot_link_itself(client: AsyncClient) -> None:
    phone = await register(client)
    link = await start(client, device_id="phone-1")
    code, body = await claim(client, phone["token"], link["code"])
    assert code == 400 and body["error"] == "invalid_request"


async def test_a_device_unlinked_before_collecting_its_token_cannot_finish(client: AsyncClient) -> None:
    phone = await register(client)
    link = await start(client)
    await claim(client, phone["token"], link["code"])
    assert (await client.delete("/v1/devices/watch-1", headers=bearer(phone["token"]))).status_code == 204
    assert (await status(client, link))[0] == 404
