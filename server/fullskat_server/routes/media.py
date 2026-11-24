"""Image attachments: upload (normalised to JPEG) and download for the owner's devices."""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse, JSONResponse

from .. import images
from ..api import ApiError, Auth, rate_limited, read_body, require_auth

log = logging.getLogger(__name__)

router = APIRouter()

CACHE_FOREVER = {"Cache-Control": "private, max-age=31536000, immutable"}


@router.post("/v1/attachments")
async def upload(request: Request, auth: Auth = Depends(require_auth)) -> JSONResponse:
    state = request.app.state
    decision = state.upload_limiter.check("upload", auth.user_id)
    if not decision.allowed:
        raise rate_limited(decision)
    content_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
    if not images.accepts(content_type):
        kinds = "image/jpeg, image/png, image/webp" + (" or image/heic" if images.HEIF_SUPPORTED else "")
        raise ApiError(415, "unsupported_media", f"send the raw image as {kinds}")
    data = await read_body(request, images.MAX_UPLOAD_BYTES)
    try:
        image = await asyncio.to_thread(images.normalize, data)
    except images.UnsupportedImage as exc:
        raise ApiError(415, "unsupported_media", f"cannot decode the image: {exc}") from None
    attachment_id = images.new_attachment_id()
    await asyncio.to_thread(images.write_files, state.settings.data_dir, auth.user_id, attachment_id, image)
    row = await state.store.add_attachment(auth.user_id, attachment_id, "image/jpeg", image.width, image.height,
                                           len(image.full))
    log.info("Attachment %s for user %s: %dx%d, %d bytes", attachment_id, auth.user_id, image.width, image.height,
             len(image.full))
    return JSONResponse(
        {"id": row["id"], "mime": row["mime"], "width": row["width"], "height": row["height"], "size": row["size"]},
        status_code=201,
    )


@router.get("/v1/attachments/{attachment_id}")
async def download(
    attachment_id: str, request: Request, size: str | None = None, auth: Auth = Depends(require_auth)
) -> FileResponse:
    if size not in (None, "", "full", "thumb"):
        raise ApiError(400, "invalid_request", "size must be thumb (or absent for the full image)")
    state = request.app.state
    row = await state.store.get_attachment(auth.user_id, attachment_id)
    path = images.attachment_path(state.settings.data_dir, auth.user_id, attachment_id, thumb=size == "thumb")
    if row is None or not path.is_file():
        raise ApiError(404, "not_found", "no such attachment")
    return FileResponse(path, media_type="image/jpeg", headers=CACHE_FOREVER)
