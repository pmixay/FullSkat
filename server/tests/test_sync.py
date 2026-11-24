"""Preferences, dialogs, message deletion and sync with tombstones and paging."""

from fastapi import FastAPI
from httpx import AsyncClient

from helpers import FakeClock, bearer, chat_body, register, token_for

NO_PREFS = {"model": None, "effort": None, "web_search": None, "figures": None}


async def sync(client: AsyncClient, token: str, since: int = 0, limit: int = 500) -> dict:
    response = await client.get("/v1/sync", params={"since": since, "limit": limit}, headers=bearer(token))
    assert response.status_code == 200, response.text
    return response.json()


# ---- preferences ----------------------------------------------------------------


async def test_prefs_default_merge_and_validation(client: AsyncClient, clock: FakeClock) -> None:
    token = await token_for(client)
    assert (await client.get("/v1/prefs", headers=bearer(token))).json() == {"prefs": NO_PREFS, "updated_at": 0}

    put = await client.put("/v1/prefs", json={"model": "claude-sonnet-5-5", "effort": "high", "color": "red"},
                           headers=bearer(token))
    assert put.status_code == 200
    expected = {**NO_PREFS, "model": "claude-sonnet-5-5", "effort": "high"}
    assert put.json()["prefs"] == expected
    assert abs(put.json()["updated_at"] - clock() * 1000) < 5000  # milliseconds, from the server's clock

    merged = await client.put("/v1/prefs", json={"web_search": False}, headers=bearer(token))
    assert merged.json()["prefs"] == {**expected, "web_search": False}
    reset = await client.put("/v1/prefs", json={"model": None}, headers=bearer(token))
    assert reset.json()["prefs"]["model"] is None
    assert (await client.get("/v1/prefs", headers=bearer(token))).json()["prefs"] == {**expected, "model": None, "web_search": False}

    for bad in ({"model": "evil/model"}, {"model": 3}, {"effort": "extreme"}, {"web_search": "yes"}, {"figures": 1}):
        response = await client.put("/v1/prefs", json=bad, headers=bearer(token))
        assert response.status_code == 400, bad
        assert response.json()["error"] == "invalid_request"
    assert (await client.put("/v1/prefs", json=[1, 2], headers=bearer(token))).status_code == 400


async def test_prefs_are_per_user_and_part_of_sync(client: AsyncClient) -> None:
    token = await token_for(client)
    other = await token_for(client, device_id="phone-2", name="Аня")
    await client.put("/v1/prefs", json={"figures": False}, headers=bearer(token))
    assert (await client.get("/v1/prefs", headers=bearer(other))).json()["prefs"] == NO_PREFS

    first = await sync(client, token)
    assert first["prefs"] == {**NO_PREFS, "figures": False} and first["seq"] == 1
    assert (await sync(client, token, since=first["seq"]))["prefs"] is None
    # Setting the same values again is no change.
    await client.put("/v1/prefs", json={"figures": False}, headers=bearer(token))
    assert (await sync(client, token, since=first["seq"])) == {
        "dialogs": [], "messages": [], "prefs": None, "seq": 1, "more": False,
    }


async def test_prefs_fill_in_what_a_chat_request_leaves_out(client: AsyncClient, app: FastAPI) -> None:
    token = await token_for(client)
    await client.put("/v1/prefs", json={"model": "claude-opus-5-5", "effort": "low", "web_search": False,
                                         "figures": False}, headers=bearer(token))
    await client.post("/v1/chat", json=chat_body(), headers=bearer(token))
    assert app.state.llm.requests[-1][1] == "claude-opus-5-5"
    assert app.state.llm.options[-1] == {"effort": "low", "web_search": False}
    assert app.state.llm.figure_flags[-1] is False
    assert "`draw` tool" not in app.state.llm.requests[-1][0][0]["content"]

    # The request wins over the prefs.
    await client.post("/v1/chat", json=chat_body(message_id="m2", answer_id="a2", model="claude-haiku-5-5",
                                                 effort="max", web_search=True), headers=bearer(token))
    assert app.state.llm.requests[-1][1] == "claude-haiku-5-5"
    assert app.state.llm.options[-1] == {"effort": "max", "web_search": True}

    # A preferred model that is not available right now falls back to the default.
    app.state.llm.model_list = ["claude-haiku-5-5", "claude-sonnet-5-5"]
    response = await client.post("/v1/chat", json=chat_body(message_id="m3", answer_id="a3"), headers=bearer(token))
    assert response.status_code == 200 and app.state.llm.requests[-1][1] == "claude-haiku-5-5"


# ---- dialogs and messages ---------------------------------------------------------


async def test_dialogs_create_rename_delete(client: AsyncClient) -> None:
    token = await token_for(client)
    created = await client.post("/v1/dialogs", json={"id": "d-1", "title": " Погода ", "created_at": 1_700_000_000_000},
                                headers=bearer(token))
    assert created.status_code == 201
    dialog = created.json()
    assert dialog["id"] == "d-1" and dialog["title"] == "Погода" and dialog["created_at"] == 1_700_000_000_000
    assert dialog["deleted"] is False and dialog["seq"] == 1 and dialog["updated_at"] > 0

    again = await client.post("/v1/dialogs", json={"id": "d-1", "title": "other"}, headers=bearer(token))
    assert again.status_code == 200 and again.json() == dialog  # idempotent

    renamed = await client.patch("/v1/dialogs/d-1", json={"title": "Погода на завтра"}, headers=bearer(token))
    assert renamed.status_code == 200 and renamed.json()["title"] == "Погода на завтра" and renamed.json()["seq"] == 2

    assert (await client.delete("/v1/dialogs/d-1", headers=bearer(token))).status_code == 204
    assert (await client.delete("/v1/dialogs/d-1", headers=bearer(token))).status_code == 204  # already a tombstone
    assert (await client.patch("/v1/dialogs/d-1", json={"title": "x"}, headers=bearer(token))).status_code == 404
    tombstone = (await sync(client, token))["dialogs"]
    assert tombstone == [{**dialog, "title": "", "deleted": True, "seq": 3, "updated_at": tombstone[0]["updated_at"]}]
    # Creating it again returns the tombstone.
    assert (await client.post("/v1/dialogs", json={"id": "d-1"}, headers=bearer(token))).json()["deleted"] is True


async def test_dialog_ids_are_validated_and_scoped_to_the_user(client: AsyncClient) -> None:
    token = await token_for(client)
    other = await token_for(client, device_id="phone-2", name="Аня")
    for bad in ("", "a/b", "x" * 65, "white space"):
        response = await client.post("/v1/dialogs", json={"id": bad}, headers=bearer(token))
        assert response.status_code == 400, bad
    assert (await client.post("/v1/dialogs", json={"id": "mine"}, headers=bearer(token))).status_code == 201
    taken = await client.post("/v1/dialogs", json={"id": "mine"}, headers=bearer(other))
    assert taken.status_code == 409 and taken.json()["error"] == "conflict"
    assert (await client.patch("/v1/dialogs/mine", json={"title": "x"}, headers=bearer(other))).status_code == 404
    assert (await client.delete("/v1/dialogs/mine", headers=bearer(other))).status_code == 404
    assert (await client.delete("/v1/dialogs/unknown", headers=bearer(token))).status_code == 404
    assert (await sync(client, other))["dialogs"] == []


async def test_deleting_a_dialog_tombstones_its_messages(client: AsyncClient) -> None:
    token = await token_for(client)
    await client.post("/v1/chat", json=chat_body("first"), headers=bearer(token))
    await client.post("/v1/chat", json=chat_body("second", message_id="m2", answer_id="a2"), headers=bearer(token))
    before = await sync(client, token)
    assert len(before["messages"]) == 4

    assert (await client.delete("/v1/dialogs/d1", headers=bearer(token))).status_code == 204
    after = await sync(client, token, since=before["seq"])
    assert [d["deleted"] for d in after["dialogs"]] == [True]
    assert sorted(m["id"] for m in after["messages"]) == ["a1", "a2", "m1", "m2"]
    assert all(m["deleted"] and m["content"] == "" and m["thinking"] == "" for m in after["messages"])
    # A chat into a deleted dialog is refused.
    late = await client.post("/v1/chat", json=chat_body("third", message_id="m3", answer_id="a3"), headers=bearer(token))
    assert late.status_code == 404


async def test_delete_message(client: AsyncClient) -> None:
    token = await token_for(client)
    other = await token_for(client, device_id="phone-2", name="Аня")
    await client.post("/v1/chat", json=chat_body(), headers=bearer(token))
    seq = (await sync(client, token))["seq"]
    assert (await client.delete("/v1/messages/a1", headers=bearer(other))).status_code == 404
    assert (await client.delete("/v1/messages/a1", headers=bearer(token))).status_code == 204
    assert (await client.delete("/v1/messages/a1", headers=bearer(token))).status_code == 204
    assert (await client.delete("/v1/messages/nope", headers=bearer(token))).status_code == 404
    changed = await sync(client, token, since=seq)
    assert [(m["id"], m["deleted"], m["content"]) for m in changed["messages"]] == [("a1", True, "")]
    assert changed["seq"] == seq + 1


# ---- sync ---------------------------------------------------------------------------


async def test_sync_pages_in_seq_order(client: AsyncClient) -> None:
    token = await token_for(client)
    for i in range(5):
        await client.post("/v1/dialogs", json={"id": f"d{i}"}, headers=bearer(token))
    await client.put("/v1/prefs", json={"effort": "high"}, headers=bearer(token))  # seq 6
    await client.patch("/v1/dialogs/d0", json={"title": "renamed"}, headers=bearer(token))  # seq 7

    pages: list[list[str]] = []
    since = 0
    while True:
        page = await sync(client, token, since=since, limit=2)
        names = [d["id"] for d in page["dialogs"]] + (["prefs"] if page["prefs"] else [])
        assert len(names) <= 2
        pages.append(names)
        assert page["seq"] == max([since] + [d["seq"] for d in page["dialogs"]] + ([6] if page["prefs"] else []))
        since = page["seq"]
        if not page["more"]:
            break
    assert pages == [["d1", "d2"], ["d3", "d4"], ["d0", "prefs"]]  # d0 moved to its new seq (7), after the prefs (6)
    assert since == 7

    nothing = await sync(client, token, since=since)
    assert nothing == {"dialogs": [], "messages": [], "prefs": None, "seq": since, "more": False}
    for bad in ({"since": -1}, {"limit": 0}, {"limit": 1001}):
        response = await client.get("/v1/sync", params=bad, headers=bearer(token))
        assert response.status_code == 400, bad


async def test_sync_from_zero_includes_tombstones(client: AsyncClient) -> None:
    token = await token_for(client)
    await client.post("/v1/dialogs", json={"id": "keep"}, headers=bearer(token))
    await client.post("/v1/dialogs", json={"id": "gone"}, headers=bearer(token))
    await client.delete("/v1/dialogs/gone", headers=bearer(token))
    everything = await sync(client, token)
    assert {(d["id"], d["deleted"]) for d in everything["dialogs"]} == {("keep", False), ("gone", True)}


async def test_another_device_of_the_user_sees_the_same_data(client: AsyncClient) -> None:
    phone = await register(client)
    link = (await client.post("/v1/link/start", json={"device_id": "watch-1", "device_kind": "watch"})).json()
    await client.post("/v1/link/claim", json={"code": link["code"]}, headers=bearer(phone["token"]))
    watch = (await client.get("/v1/link/status", params={"link_id": link["link_id"]})).json()["token"]
    await client.post("/v1/chat", json=chat_body("from the watch"), headers=bearer(watch))
    synced = await sync(client, phone["token"])
    assert [m["content"] for m in synced["messages"]] == ["from the watch", "Hello world"]
    assert synced["dialogs"][0]["title"] == "from the watch"
