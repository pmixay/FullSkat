import json
from pathlib import Path

import pytest

from fullskat_server.auth import (
    InvalidPasscode,
    StateStore,
    generate_token,
    hash_passcode,
    token_hash,
    verify_passcode,
)


def test_hash_and_verify_roundtrip() -> None:
    record = hash_passcode("1234")
    assert record.iterations == 200_000
    assert record.salt and record.hash and record.set_at
    assert verify_passcode("1234", record)
    assert not verify_passcode("1235", record)


def test_hashes_use_random_salts() -> None:
    first, second = hash_passcode("0000"), hash_passcode("0000")
    assert first.salt != second.salt
    assert first.hash != second.hash


def test_token_is_urlsafe_and_long() -> None:
    token = generate_token()
    assert len(token) >= 43
    assert all(ch.isalnum() or ch in "-_" for ch in token)


async def test_first_setup_then_verify(tmp_path: Path) -> None:
    path = tmp_path / "state.json"
    store = StateStore(path)
    assert not store.is_paired

    first = await store.pair("2468", "dev-1", "Watch")
    assert first.first_setup
    assert store.is_paired
    assert store.lookup_token(first.token).device_name == "Watch"

    raw = json.loads(path.read_text())
    assert raw["passcode"]["iterations"] == 200_000
    assert first.token not in path.read_text()
    assert token_hash(first.token) in raw["tokens"]

    # A fresh store loads the same state from disk.
    reloaded = StateStore(path)
    assert reloaded.is_paired
    assert reloaded.lookup_token(first.token).device_id == "dev-1"
    assert reloaded.lookup_token("nope") is None

    second = await reloaded.pair("2468", "dev-2", "Other")
    assert not second.first_setup
    assert second.token != first.token


async def test_invalid_passcode_rejected(tmp_path: Path) -> None:
    store = StateStore(tmp_path / "state.json")
    await store.pair("1111", "dev-1", "Watch")
    with pytest.raises(InvalidPasscode):
        await store.pair("2222", "dev-2", "Watch")
    assert store.lookup_token("anything") is None


async def test_repair_replaces_device_token(tmp_path: Path) -> None:
    path = tmp_path / "state.json"
    store = StateStore(path)
    old = await store.pair("1111", "dev-1", "Watch")
    new = await store.pair("1111", "dev-1", "Watch renamed")
    assert store.lookup_token(old.token) is None
    assert store.lookup_token(new.token).device_name == "Watch renamed"
    raw = json.loads(path.read_text())
    assert len(raw["tokens"]) == 1
