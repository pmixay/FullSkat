import json
from pathlib import Path

import pytest

from fullskat_server.auth import (
    generate_token,
    hash_passcode,
    read_legacy_state,
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
    assert token_hash(token) == token_hash(token) and len(token_hash(token)) == 64


def test_read_legacy_state(tmp_path: Path) -> None:
    record = hash_passcode("2468", iterations=1000)
    path = tmp_path / "state.json"
    path.write_text(json.dumps({
        "passcode": record.to_json(),
        "tokens": {
            token_hash("t1"): {"device_id": "watch-1", "device_name": "Galaxy Watch7", "created_at": "2026-10-01T10:00:00+00:00"},
            token_hash("t2"): {"device_id": "", "device_name": ""},
        },
    }))
    state = read_legacy_state(path)
    assert state.passcode == record and verify_passcode("2468", state.passcode)
    assert [(t.token_hash, t.device_id, t.device_name) for t in state.tokens] == [
        (token_hash("t1"), "watch-1", "Galaxy Watch7"),
        (token_hash("t2"), "", ""),
    ]

    path.write_text(json.dumps({"passcode": None, "tokens": {}}))
    assert read_legacy_state(path).passcode is None

    path.write_text("{broken")
    with pytest.raises(ValueError):
        read_legacy_state(path)
