"""The live event stream: `changed` with the newest seq, `ping`, `revoked`."""

import asyncio

from fastapi import FastAPI
from httpx import AsyncClient

from fullskat_server.events import EventHub
from helpers import REGISTER, AsgiStream, bearer, register, token_for


def events(app: FastAPI, token: str) -> AsgiStream:
    return AsgiStream(app, "GET", "/v1/events", headers=bearer(token))


async def test_changed_on_connect_and_after_each_change(app: FastAPI, client: AsyncClient) -> None:
    token = await token_for(client)
    await client.put("/v1/prefs", json={"effort": "low"}, headers=bearer(token))
    async with events(app, token) as stream:
        assert stream.status == 200
        assert await stream.next_event() == ("changed", {"seq": 1})  # where the user is now
        await client.post("/v1/dialogs", json={"id": "d1"}, headers=bearer(token))
        assert await stream.next_event() == ("changed", {"seq": 2})
        await client.patch("/v1/dialogs/d1", json={"title": "t"}, headers=bearer(token))
        assert await stream.next_event() == ("changed", {"seq": 3})


async def test_a_burst_of_changes_is_coalesced(app: FastAPI, client: AsyncClient) -> None:
    app.state.timing.coalesce = 0.2
    token = await token_for(client)
    async with events(app, token) as stream:
        await stream.next_event()
        await asyncio.gather(*(client.post("/v1/dialogs", json={"id": f"d{i}"}, headers=bearer(token)) for i in range(5)))
        assert await stream.next_event() == ("changed", {"seq": 5})


async def test_only_the_users_own_changes(app: FastAPI, client: AsyncClient) -> None:
    app.state.timing.ping_interval = 0.2
    mine = await token_for(client)
    theirs = await token_for(client, device_id="phone-2", name="Аня")
    async with events(app, mine) as stream:
        await stream.next_event()
        await client.post("/v1/dialogs", json={"id": "theirs"}, headers=bearer(theirs))
        assert await stream.next_event() == ("ping", {})


async def test_ping_keeps_the_connection_alive(app: FastAPI, client: AsyncClient) -> None:
    app.state.timing.ping_interval = 0.05
    token = await token_for(client)
    async with events(app, token) as stream:
        await stream.next_event()
        assert await stream.next_event() == ("ping", {})
        assert await stream.next_event() == ("ping", {})


async def test_revoked_when_the_device_is_unlinked(app: FastAPI, client: AsyncClient) -> None:
    phone = await register(client)
    other_phone = await token_for(client, device_id="phone-2", name="Миша")  # a second account, unaffected
    link = (await client.post("/v1/link/start", json={"device_id": "watch-1", "device_kind": "watch"})).json()
    await client.post("/v1/link/claim", json={"code": link["code"]}, headers=bearer(phone["token"]))
    watch = (await client.get("/v1/link/status", params={"link_id": link["link_id"]})).json()["token"]
    app.state.timing.ping_interval = 0.1
    async with events(app, watch) as watch_stream, events(app, phone["token"]) as phone_stream, \
            events(app, other_phone) as unrelated:
        for stream in (watch_stream, phone_stream, unrelated):
            assert (await stream.next_event())[0] == "changed"
        assert (await client.delete("/v1/devices/watch-1", headers=bearer(phone["token"]))).status_code == 204
        event = await watch_stream.next_event()
        while event == ("ping", {}):
            event = await watch_stream.next_event()
        assert event == ("revoked", {})
        assert await watch_stream.next_event() is None  # the stream ends
        for stream in (phone_stream, unrelated):  # still open: they only ever ping
            assert {(await stream.next_event())[0] for _ in range(3)} == {"ping"}
    assert app.state.hub.count(phone["user"]["id"]) == 0  # every subscription was cleaned up


async def test_revoked_when_the_device_registers_again(app: FastAPI, client: AsyncClient) -> None:
    token = await token_for(client)
    async with events(app, token) as stream:
        await stream.next_event()
        await client.post("/v1/register", json=REGISTER)
        assert await stream.next_event() == ("revoked", {})


async def test_events_need_a_valid_token(app: FastAPI, client: AsyncClient) -> None:
    async with AsgiStream(app, "GET", "/v1/events", headers=bearer("nope")) as stream:
        assert stream.status == 401


async def test_hub_keeps_only_the_newest_seq() -> None:
    hub = EventHub()
    sub = hub.subscribe("u", "dev", 3)
    hub.publish("u", 2)  # older than what the stream knows: no wake-up
    assert not await sub.wait(0.01)
    hub.publish("u", 5)
    hub.publish("u", 7)
    hub.publish("other", 9)
    hub.publish("u", None)
    assert await sub.wait(0.01) and sub.seq == 7
    hub.revoke_device("dev")
    assert await sub.wait(0.01) and sub.revoked
    hub.unsubscribe(sub)
    assert hub.count("u") == 0
