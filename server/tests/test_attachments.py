"""Image attachments: upload and normalisation, ownership, limits, and images reaching each provider."""

import base64
import io
import json
from pathlib import Path
from typing import Any, AsyncIterator

import httpx
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from PIL import Image, ImageCms

from fullskat_server import images
from fullskat_server.app import create_app
from fullskat_server.config import Settings
from fullskat_server.llm import (
    IMAGE_CHARS,
    ChatImage,
    KimiLLM,
    OpenAILLM,
    OpenRouterLLM,
    anthropic_message,
    chat_completions_message,
    responses_message,
    trim_history,
)
from helpers import FakeLLM, bearer, chat_body, jpeg_bytes, token_for
from test_llm import final_message, llm_with, make_settings, sse


async def upload(client: AsyncClient, token: str, data: bytes, content_type: str = "image/jpeg") -> httpx.Response:
    return await client.post("/v1/attachments", content=data, headers={**bearer(token), "Content-Type": content_type})


def decode(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data))
    img.load()
    return img


# ---- upload and normalisation ------------------------------------------------------


async def test_upload_normalises_and_serves_the_image(client: AsyncClient, settings: Settings) -> None:
    token = await token_for(client)
    exif = Image.Exif()
    exif[0x0112] = 6  # orientation: rotate 90° clockwise to view
    exif[0x010F] = "SecretCam"  # camera make, must not survive
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    original = jpeg_bytes(3000, 2000, exif=exif.tobytes(), icc_profile=profile)

    response = await upload(client, token, original)
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["id"].startswith("a_") and body["mime"] == "image/jpeg"
    assert (body["width"], body["height"]) == (1045, 1568)  # turned upright, long side 1568
    stored = (await client.get(f"/v1/attachments/{body['id']}", headers=bearer(token)))
    assert stored.status_code == 200 and stored.headers["content-type"] == "image/jpeg"
    assert stored.headers["cache-control"] == "private, max-age=31536000, immutable"
    assert len(stored.content) == body["size"]
    image = decode(stored.content)
    assert image.size == (1045, 1568) and image.format == "JPEG"
    assert not image.getexif() and "exif" not in image.info and "icc_profile" not in image.info
    assert b"SecretCam" not in stored.content

    thumb = await client.get(f"/v1/attachments/{body['id']}", params={"size": "thumb"}, headers=bearer(token))
    assert thumb.status_code == 200 and max(decode(thumb.content).size) == 320
    assert (await client.get(f"/v1/attachments/{body['id']}", params={"size": "huge"}, headers=bearer(token))).status_code == 400
    files = sorted(p.name for p in (settings.data_dir / "attachments").rglob("*.jpg"))
    assert files == [f"{body['id']}.jpg", f"{body['id']}.thumb.jpg"]


async def test_small_png_with_transparency_and_webp(client: AsyncClient) -> None:
    token = await token_for(client)
    png = io.BytesIO()
    Image.new("RGBA", (40, 30), (0, 0, 0, 0)).save(png, "PNG")
    response = await upload(client, token, png.getvalue(), "image/png")
    assert response.status_code == 201 and (response.json()["width"], response.json()["height"]) == (40, 30)
    pixel = decode((await client.get(f"/v1/attachments/{response.json()['id']}", headers=bearer(token))).content).getpixel((5, 5))
    assert all(channel > 240 for channel in pixel)  # transparency became white

    webp = io.BytesIO()
    Image.new("RGB", (50, 50), (0, 128, 0)).save(webp, "WEBP")
    assert (await upload(client, token, webp.getvalue(), "image/webp; charset=binary")).status_code == 201

    sixteen = io.BytesIO()
    Image.new("I;16", (20, 20), 40000).save(sixteen, "PNG")
    assert (await upload(client, token, sixteen.getvalue(), "image/png")).status_code == 201


async def test_unsupported_uploads_are_415(client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    token = await token_for(client)
    for data, kind in [(b"hello", "text/plain"), (jpeg_bytes(), "application/octet-stream"), (jpeg_bytes(), ""),
                       (b"\x89PNG not really", "image/png"), (b"", "image/jpeg")]:
        response = await upload(client, token, data, kind)
        assert response.status_code == 415, kind
        assert response.json()["error"] == "unsupported_media"
    gif = io.BytesIO()
    Image.new("RGB", (4, 4)).save(gif, "GIF")
    assert (await upload(client, token, gif.getvalue(), "image/png")).status_code == 415  # only the allowed decoders
    bomb = io.BytesIO()
    Image.new("1", (8000, 7000)).save(bomb, "PNG")
    response = await upload(client, token, bomb.getvalue(), "image/png")
    assert response.status_code == 415 and "too large" in response.json()["message"]
    monkeypatch.setattr(images, "HEIF_SUPPORTED", False)
    assert (await upload(client, token, b"anything", "image/heic")).status_code == 415


async def test_heic_upload_when_pillow_heif_is_installed(client: AsyncClient) -> None:
    pillow_heif = pytest.importorskip("pillow_heif")
    try:
        heif = pillow_heif.from_pillow(Image.new("RGB", (64, 32), (10, 20, 200)))
        buffer = io.BytesIO()
        heif.save(buffer, quality=80)
    except Exception as exc:  # the wheel may lack an encoder
        pytest.skip(f"cannot encode HEIC here: {exc}")
    token = await token_for(client)
    response = await upload(client, token, buffer.getvalue(), "image/heic")
    assert response.status_code == 201, response.text
    assert (response.json()["width"], response.json()["height"]) == (64, 32)


async def test_upload_size_cap(client: AsyncClient) -> None:
    token = await token_for(client)
    too_big = b"\xff" * (images.MAX_UPLOAD_BYTES + 1)
    declared = await upload(client, token, too_big)
    assert declared.status_code == 413 and declared.json()["error"] == "payload_too_large"
    assert "10 MB" in declared.json()["message"]

    async def chunks() -> AsyncIterator[bytes]:  # no Content-Length: counted while reading
        for _ in range(11):
            yield b"\xff" * (1024 * 1024)

    streamed = await client.post("/v1/attachments", content=chunks(), headers={**bearer(token), "Content-Type": "image/jpeg"})
    assert streamed.status_code == 413


async def test_only_the_owner_can_read_an_attachment(client: AsyncClient) -> None:
    owner = await token_for(client)
    stranger = await token_for(client, device_id="phone-2", name="Аня")
    attachment = (await upload(client, owner, jpeg_bytes())).json()["id"]
    for params in ({}, {"size": "thumb"}):
        response = await client.get(f"/v1/attachments/{attachment}", params=params, headers=bearer(stranger))
        assert response.status_code == 404 and response.json()["error"] == "not_found"
    assert (await client.get("/v1/attachments/a_nope", headers=bearer(owner))).status_code == 404
    assert (await client.get("/v1/attachments/..%2F..%2Ffullskat.db", headers=bearer(owner))).status_code == 404
    assert (await client.get(f"/v1/attachments/{attachment}")).status_code == 401
    # Nor use it in a message.
    response = await client.post("/v1/chat", json=chat_body("look", attachments=[attachment]), headers=bearer(stranger))
    assert response.status_code == 400


async def test_uploads_are_limited_per_user(tmp_path: Path) -> None:
    app = create_app(Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="k", uploads_per_hour=2))
    app.state.llm = FakeLLM()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        token = await token_for(client)
        for _ in range(2):
            assert (await upload(client, token, jpeg_bytes())).status_code == 201
        limited = await upload(client, token, jpeg_bytes())
        assert limited.status_code == 429 and limited.json()["error"] == "rate_limited"
        assert int(limited.headers["retry-after"]) > 60
        other = await token_for(client, device_id="phone-2", name="Аня")
        assert (await upload(client, other, jpeg_bytes())).status_code == 201


# ---- attachments in chat ------------------------------------------------------------


async def test_images_of_the_recent_turns_reach_the_model(client: AsyncClient, app: FastAPI, settings: Settings) -> None:
    token = await token_for(client)
    ids = [(await upload(client, token, jpeg_bytes(color=(i * 60, 0, 0)))).json()["id"] for i in range(4)]
    app.state.llm = FakeLLM(deltas=["ok"])
    for turn, attachments in enumerate([[ids[0]], [ids[1]], [ids[2], ids[3]]]):
        response = await client.post(
            "/v1/chat", json=chat_body("" if turn == 2 else f"photo {turn}", message_id=f"m{turn}",
                                       answer_id=f"a{turn}", attachments=attachments),
            headers=bearer(token),
        )
        assert response.status_code == 200, response.text
    question = (await client.get("/v1/sync", headers=bearer(token))).json()["messages"][-2]
    assert question["content"] == "" and [a["id"] for a in question["attachments"]] == ids[2:]
    assert question["attachments"][0] == {"id": ids[2], "mime": "image/jpeg", "width": 64, "height": 48}

    sent = app.state.llm.requests[-1][0][1:]
    assert [m["role"] for m in sent] == ["user", "assistant", "user", "assistant", "user"]
    assert sent[0] == {"role": "user", "content": "photo 0\n[image]"}  # older than the 2 newest image turns
    assert sent[2]["content"] == "photo 1" and len(sent[2]["images"]) == 1
    assert sent[4]["content"] == "" and len(sent[4]["images"]) == 2
    stored = images.attachment_path(settings.data_dir, _user(app, token), ids[2])
    assert sent[4]["images"][0] == ChatImage(base64.b64encode(stored.read_bytes()).decode())


def _user(app: FastAPI, token: str) -> str:
    from fullskat_server.auth import token_hash

    with app.state.store.db.read() as con:
        return con.execute("SELECT user_id FROM devices WHERE token_hash = ?", (token_hash(token),)).fetchone()[0]


async def test_missing_image_files_become_placeholders(client: AsyncClient, app: FastAPI, settings: Settings) -> None:
    token = await token_for(client)
    attachment = (await upload(client, token, jpeg_bytes())).json()["id"]
    images.delete_files(settings.data_dir, _user(app, token), attachment)
    app.state.llm = FakeLLM(deltas=["ok"])
    response = await client.post("/v1/chat", json=chat_body("", attachments=[attachment]), headers=bearer(token))
    assert response.status_code == 200
    assert app.state.llm.requests[-1][0][-1] == {"role": "user", "content": "[image]"}


async def test_deleting_messages_removes_unused_attachment_files(client: AsyncClient, app: FastAPI, settings: Settings) -> None:
    token = await token_for(client)
    shared = (await upload(client, token, jpeg_bytes())).json()["id"]
    await client.post("/v1/chat", json=chat_body("one", attachments=[shared]), headers=bearer(token))
    await client.post("/v1/chat", json=chat_body("two", dialog_id="d2", message_id="m2", answer_id="a2",
                                                 attachments=[shared]), headers=bearer(token))
    path = images.attachment_path(settings.data_dir, _user(app, token), shared)
    await client.delete("/v1/messages/m1", headers=bearer(token))
    assert path.exists()  # still used by m2
    await client.delete("/v1/dialogs/d2", headers=bearer(token))
    assert not path.exists() and not path.with_name(f"{shared}.thumb.jpg").exists()
    assert (await client.get(f"/v1/attachments/{shared}", headers=bearer(token))).status_code == 404


# ---- provider formats ---------------------------------------------------------------

PICTURE = ChatImage("QUJD")  # base64 of "ABC"
TURN = {"role": "user", "content": "Что на фото?", "images": [PICTURE]}


def test_converters_build_each_providers_image_format() -> None:
    assert anthropic_message(TURN) == {"role": "user", "content": [
        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "QUJD"}},
        {"type": "text", "text": "Что на фото?"},
    ]}
    assert chat_completions_message(TURN) == {"role": "user", "content": [
        {"type": "text", "text": "Что на фото?"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,QUJD"}},
    ]}
    assert responses_message(TURN) == {"role": "user", "content": [
        {"type": "input_text", "text": "Что на фото?"},
        {"type": "input_image", "image_url": "data:image/jpeg;base64,QUJD", "detail": "auto"},
    ]}
    only_image = {"role": "user", "content": "", "images": [PICTURE]}
    assert [b["type"] for b in anthropic_message(only_image)["content"]] == ["image"]  # no empty text block
    assert [p["type"] for p in chat_completions_message(only_image)["content"]] == ["image_url"]
    assert [p["type"] for p in responses_message(only_image)["content"]] == ["input_image"]
    plain = {"role": "assistant", "content": "hi"}
    assert anthropic_message(plain) == chat_completions_message(plain) == responses_message(plain) == plain
    item = {"type": "function_call_output", "call_id": "c", "output": "ok"}
    assert responses_message(item) == item  # tool-loop items pass through


def test_trim_history_counts_images() -> None:
    history = [
        {"role": "user", "content": "x" * 500},
        {"role": "assistant", "content": "y" * 500},
        {"role": "user", "content": "z", "images": [PICTURE, PICTURE]},
    ]
    assert trim_history(history, 10, 2 * IMAGE_CHARS + 1001) == history
    assert trim_history(history, 10, 2 * IMAGE_CHARS + 600) == history[1:]
    assert trim_history(history, 10, 10) == [history[-1]]  # the newest is always kept, images and all


def test_chat_completions_bodies_carry_images(tmp_path: Path) -> None:
    messages = [{"role": "system", "content": "S"}, TURN]
    for llm in (OpenRouterLLM(make_settings(tmp_path, openrouter_api_key="o"), httpx.AsyncClient()),
                KimiLLM(make_settings(tmp_path, kimi_api_key="k"), httpx.AsyncClient())):
        body = llm._body(messages, "m")
        assert body["messages"] == [{"role": "system", "content": "S"}, chat_completions_message(TURN)]
        assert "images" not in json.dumps(body)


def test_responses_body_carries_images(tmp_path: Path) -> None:
    llm = OpenAILLM(make_settings(tmp_path, openai_api_key="g"), httpx.AsyncClient())
    body = llm._body([{"role": "system", "content": "S"}, {"role": "assistant", "content": "hi"}, TURN], "gpt-6.1-terra")
    assert body["instructions"] == "S"
    assert body["input"] == [{"role": "assistant", "content": "hi"}, responses_message(TURN)]


async def test_anthropic_request_carries_images(tmp_path: Path) -> None:
    llm, fake = llm_with(make_settings(tmp_path), [(["Кот."], final_message())])
    [i async for i in llm.stream([{"role": "system", "content": "S"}, TURN], "claude-haiku-5-5")]
    assert fake.requests[0]["system"] == "S"
    assert fake.requests[0]["messages"] == [anthropic_message(TURN)]


async def test_openrouter_sends_images_over_the_wire_in_the_tool_loop(tmp_path: Path) -> None:
    sent: list[dict[str, Any]] = []
    draw = sse(
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "c1", "function": {
            "name": "draw", "arguments": json.dumps({"items": [{"type": "point", "at": [0, 0]}]})}}]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    )
    answer = sse({"choices": [{"delta": {"content": "Вот."}, "finish_reason": "stop"}]})
    scripted = [draw, answer]

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(json.loads(request.content))
        return httpx.Response(200, content=scripted.pop(0))

    llm = OpenRouterLLM(make_settings(tmp_path, openrouter_api_key="o", figures=True),
                        httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    items = [i async for i in llm.stream([TURN], "openai/gpt-6.1-terra")]
    assert "Вот." in items
    for body in sent:  # the first request and the follow-up after the draw call
        assert body["messages"][0] == chat_completions_message(TURN)
    assert sent[1]["messages"][-1]["role"] == "tool"
