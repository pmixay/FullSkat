"""Sign-up with the passcode, the session (/v1/me) and the user's devices."""

from pathlib import Path

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from helpers import REGISTER, FakeClock, FakeLLM, bearer, register, token_for


async def test_first_registration_sets_the_passcode(client: AsyncClient) -> None:
    assert (await client.get("/healthz")).json() == {"ok": True, "users": 0}
    first = await client.post("/v1/register", json=REGISTER)
    assert first.status_code == 201
    body = first.json()
    assert body["first_setup"] is True
    assert body["server_name"] == "FullSkat"
    assert body["user"]["name"] == "Миша" and body["user"]["id"].startswith("u_")
    assert body["device"] == {
        "id": "phone-1", "kind": "phone", "name": "Pixel 9",
        "created_at": body["user"]["created_at"], "last_seen": body["user"]["created_at"], "current": True,
    }
    assert body["model"] == "claude-haiku-5-5" and body["effort"] == "medium"
    assert body["web_search"] is True and body["figures"] is True
    assert len(body["token"]) >= 43

    # The passcode is an invitation: anyone who knows it gets their own account.
    second = await client.post("/v1/register", json={**REGISTER, "name": "Аня", "device_id": "phone-2"})
    assert second.status_code == 201
    assert second.json()["first_setup"] is False
    assert second.json()["user"]["id"] != body["user"]["id"]
    assert (await client.get("/healthz")).json() == {"ok": True, "users": 2}

    wrong = await client.post("/v1/register", json={**REGISTER, "passcode": "9999", "device_id": "phone-3"})
    assert wrong.status_code == 401 and wrong.json()["error"] == "invalid_passcode"


async def test_registering_a_known_device_returns_its_account_with_a_fresh_token(client: AsyncClient) -> None:
    first = await register(client)
    again = await client.post("/v1/register", json={**REGISTER, "name": "Someone else", "device_name": "Pixel 10"})
    assert again.status_code == 200
    body = again.json()
    assert body["user"] == first["user"]  # same user, not renamed
    assert body["device"]["name"] == "Pixel 10"
    assert body["token"] != first["token"]
    assert (await client.get("/v1/me", headers=bearer(first["token"]))).status_code == 401
    assert (await client.get("/v1/me", headers=bearer(body["token"]))).status_code == 200


async def test_register_validates_its_fields(client: AsyncClient, app: FastAPI) -> None:
    from fullskat_server.ratelimit import PairLimiter

    app.state.register_limiter = PairLimiter(per_minute=100, lock_failures=10, lock_minutes=15)  # malformed tries count
    for bad in ("123", "123456789", "12ab", "١٢٣٤"):
        response = await client.post("/v1/register", json={**REGISTER, "passcode": bad})
        assert response.status_code == 400, bad
        assert response.json()["error"] == "invalid_passcode_format"
    for extra in ({"name": ""}, {"name": "   "}, {"name": "x" * 41}, {"device_kind": "fridge"}, {"device_id": ""}):
        response = await client.post("/v1/register", json={**REGISTER, **extra})
        assert response.status_code == 400, extra
        assert response.json()["error"] == "invalid_request"
    assert (await client.post("/v1/register", json={"passcode": "1234"})).json()["error"] == "invalid_request"
    assert (await client.post("/v1/register", content=b"{nope")).json()["error"] == "invalid_request"
    assert (await client.get("/healthz")).json()["users"] == 0


async def test_register_rate_limited_on_sixth_attempt(client: AsyncClient) -> None:
    for i in range(5):
        response = await client.post("/v1/register", json={**REGISTER, "device_id": f"p{i}"})
        assert response.status_code == 201
    sixth = await client.post("/v1/register", json={**REGISTER, "device_id": "p6"})
    assert sixth.status_code == 429
    assert sixth.json()["error"] == "rate_limited"
    assert int(sixth.headers["retry-after"]) >= 1


async def test_register_lockout_after_consecutive_failures(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, data_dir=tmp_path, pair_per_min=100, pair_lock_failures=3)
    app = create_app(settings=settings)
    app.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        await register(client)
        for _ in range(3):
            assert (await client.post("/v1/register", json={**REGISTER, "passcode": "0000"})).status_code == 401
        locked = await client.post("/v1/register", json=REGISTER)
        assert locked.status_code == 429
        assert locked.json()["error"] == "locked"
        assert int(locked.headers["retry-after"]) > 60


async def test_pairing_is_gone(client: AsyncClient) -> None:
    response = await client.post("/v1/pair", json={"passcode": "1234", "device_id": "w"})
    assert response.status_code == 410 and response.json()["error"] == "gone"


async def test_me_and_rename(client: AsyncClient) -> None:
    body = await register(client)
    me = await client.get("/v1/me", headers=bearer(body["token"]))
    assert me.status_code == 200
    data = me.json()
    assert data["user"] == body["user"]
    assert data["device"]["id"] == "phone-1" and data["device"]["current"] is True
    assert data["server_name"] == "FullSkat"
    assert data["models"] == body["models"] and data["model_efforts"] == body["model_efforts"]
    assert data["providers"]["anthropic"]["ok"] is True
    assert data["web_search"] is True and data["figures"] is True
    assert data["limits"] == {"chat_per_min": 20, "chat_per_day": 500}

    renamed = await client.patch("/v1/me", json={"name": "  Михаил  "}, headers=bearer(body["token"]))
    assert renamed.status_code == 200 and renamed.json()["user"]["name"] == "Михаил"
    assert (await client.get("/v1/me", headers=bearer(body["token"]))).json()["user"]["name"] == "Михаил"
    bad = await client.patch("/v1/me", json={"name": ""}, headers=bearer(body["token"]))
    assert bad.status_code == 400


async def test_devices_list_and_unlink(client: AsyncClient) -> None:
    phone = await register(client)
    link = (await client.post("/v1/link/start", json={"device_id": "watch-1", "device_name": "Watch"})).json()
    assert (await client.post("/v1/link/claim", json={"code": link["code"]}, headers=bearer(phone["token"]))).status_code == 200
    watch = (await client.get(f"/v1/link/status?link_id={link['link_id']}")).json()
    assert watch["status"] == "linked"

    listed = (await client.get("/v1/devices", headers=bearer(phone["token"]))).json()["devices"]
    assert [(d["id"], d["kind"], d["current"]) for d in listed] == [("phone-1", "phone", True), ("watch-1", "watch", False)]
    from_watch = (await client.get("/v1/devices", headers=bearer(watch["token"]))).json()["devices"]
    assert [d["current"] for d in from_watch] == [False, True]

    # Another user can neither see nor unlink them.
    other = await token_for(client, device_id="phone-other", name="Other")
    assert (await client.delete("/v1/devices/watch-1", headers=bearer(other))).status_code == 404
    assert len((await client.get("/v1/devices", headers=bearer(other))).json()["devices"]) == 1

    assert (await client.delete("/v1/devices/watch-1", headers=bearer(phone["token"]))).status_code == 204
    gone = await client.get("/v1/me", headers=bearer(watch["token"]))
    assert gone.status_code == 401 and gone.json()["error"] == "invalid_token"
    assert (await client.delete("/v1/devices/watch-1", headers=bearer(phone["token"]))).status_code == 404

    # Unlinking the calling device is signing out.
    assert (await client.delete("/v1/devices/phone-1", headers=bearer(phone["token"]))).status_code == 204
    assert (await client.get("/v1/me", headers=bearer(phone["token"]))).status_code == 401


async def test_last_seen_is_refreshed_at_most_once_a_minute(client: AsyncClient, clock: FakeClock) -> None:
    body = await register(client)
    created = body["device"]["last_seen"]
    clock.advance(30)
    assert (await client.get("/v1/me", headers=bearer(body["token"]))).json()["device"]["last_seen"] == created
    clock.advance(31)
    seen = (await client.get("/v1/me", headers=bearer(body["token"]))).json()["device"]["last_seen"]
    assert seen >= created + 61_000


async def test_concurrent_first_registrations_agree_on_one_passcode(app: FastAPI) -> None:
    import asyncio

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        results = await asyncio.gather(*(
            client.post("/v1/register", json={**REGISTER, "passcode": code, "device_id": f"p-{code}"})
            for code in ("1111", "2222", "3333")
        ))
    statuses = sorted(r.status_code for r in results)
    assert statuses == [201, 401, 401]  # exactly one set the passcode; the others did not match it
    assert sum(1 for r in results if r.status_code == 201 and r.json()["first_setup"]) == 1
