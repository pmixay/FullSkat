"""Uploaded images: decode, apply the EXIF orientation, strip metadata, scale down, store as JPEG + thumbnail.

Files live under DATA_DIR/attachments/<user_id>/<attachment_id>.jpg (and .thumb.jpg). HEIC/HEIF decodes
only when the optional `pillow-heif` package is installed (`pip install ".[heic]"`).
"""

from __future__ import annotations

import base64
import io
import logging
import os
import secrets
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageCms, ImageOps, UnidentifiedImageError

log = logging.getLogger(__name__)

try:  # optional: HEIC photos from phones
    from pillow_heif import register_heif_opener

    register_heif_opener()
    HEIF_SUPPORTED = True
except ImportError:  # pragma: no cover - depends on the installation
    HEIF_SUPPORTED = False

MAX_UPLOAD_BYTES = 10 * 1024 * 1024
MAX_SIDE = 1568  # long side of the stored image; also what Claude recommends as the largest useful size
THUMB_SIDE = 320
JPEG_QUALITY = 85
THUMB_QUALITY = 80
MAX_PIXELS = 50_000_000  # refuse decompression bombs before decoding

CONTENT_TYPES = {
    "image/jpeg": "JPEG",
    "image/png": "PNG",
    "image/webp": "WEBP",
    "image/heic": "HEIF",
    "image/heif": "HEIF",
}
HEIF_TYPES = frozenset({"image/heic", "image/heif"})


class UnsupportedImage(ValueError):
    """The upload cannot be decoded as one of the accepted image formats."""


@dataclass(frozen=True)
class Normalized:
    full: bytes  # JPEG, at most MAX_SIDE px on the long side
    thumb: bytes  # JPEG, at most THUMB_SIDE px
    width: int
    height: int


def accepts(content_type: str) -> bool:
    """Whether an upload with this Content-Type can be decoded by this installation."""
    if content_type in HEIF_TYPES:
        return HEIF_SUPPORTED
    return content_type in CONTENT_TYPES


def _decoders() -> list[str]:
    formats = ["JPEG", "PNG", "WEBP"]
    if HEIF_SUPPORTED:
        formats.append("HEIF")
    return formats


def _to_srgb(img: Image.Image) -> Image.Image:
    """Convert an image with an embedded ICC profile (Display P3 photos, CMYK) to sRGB, best effort."""
    icc = img.info.get("icc_profile")
    if not icc or img.mode not in ("RGB", "RGBA", "CMYK"):
        return img
    try:
        source = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        target = ImageCms.createProfile("sRGB")
        converted = ImageCms.profileToProfile(img, source, target, outputMode="RGBA" if img.mode == "RGBA" else "RGB")
        return converted if converted is not None else img
    except (ImageCms.PyCMSError, OSError, ValueError, TypeError) as exc:
        log.debug("Ignoring an unusable ICC profile: %s", exc)
        return img


def _flatten(img: Image.Image) -> Image.Image:
    """Plain RGB: transparency goes onto white, 16-bit and palette images are reduced."""
    if img.mode in ("I;16", "I;16B", "I;16L", "I"):
        img = img.point(lambda value: value / 256).convert("L")
    if img.mode in ("RGBA", "LA", "PA") or (img.mode == "P" and "transparency" in img.info):
        rgba = img.convert("RGBA")
        background = Image.new("RGB", rgba.size, (255, 255, 255))
        background.paste(rgba, mask=rgba.getchannel("A"))
        return background
    return img if img.mode == "RGB" else img.convert("RGB")


def _jpeg(img: Image.Image, quality: int) -> bytes:
    img.info = {}  # no EXIF, ICC, XMP or comments in what we store
    buffer = io.BytesIO()
    img.save(buffer, "JPEG", quality=quality, optimize=True)
    return buffer.getvalue()


def normalize(data: bytes) -> Normalized:
    """Decode an upload and re-encode it the way the server stores it. CPU-bound: run it in a thread."""
    try:
        with Image.open(io.BytesIO(data), formats=_decoders()) as opened:
            if opened.width * opened.height > MAX_PIXELS:
                raise UnsupportedImage(f"image too large ({opened.width}x{opened.height})")
            if opened.format == "JPEG":
                opened.draft("RGB", (MAX_SIDE * 2, MAX_SIDE * 2))  # decode big camera JPEGs at a reduced scale
            img = ImageOps.exif_transpose(opened)  # a loaded copy, turned upright
        img = _flatten(_to_srgb(img))
        img.thumbnail((MAX_SIDE, MAX_SIDE), Image.Resampling.LANCZOS)
        thumb = img.copy()
        thumb.thumbnail((THUMB_SIDE, THUMB_SIDE), Image.Resampling.LANCZOS)
        return Normalized(_jpeg(img, JPEG_QUALITY), _jpeg(thumb, THUMB_QUALITY), img.width, img.height)
    except UnsupportedImage:
        raise
    except (UnidentifiedImageError, Image.DecompressionBombError, OSError, ValueError, SyntaxError, TypeError) as exc:
        raise UnsupportedImage(str(exc) or "cannot decode the image") from exc


# ---- files --------------------------------------------------------------------


def new_attachment_id() -> str:
    return "a_" + secrets.token_hex(12)


def attachment_path(data_dir: Path, user_id: str, attachment_id: str, thumb: bool = False) -> Path:
    suffix = ".thumb.jpg" if thumb else ".jpg"
    return Path(data_dir) / "attachments" / user_id / f"{attachment_id}{suffix}"


def _write(path: Path, data: bytes) -> None:
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with open(tmp, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)


def write_files(data_dir: Path, user_id: str, attachment_id: str, image: Normalized) -> None:
    full = attachment_path(data_dir, user_id, attachment_id)
    full.parent.mkdir(parents=True, exist_ok=True)
    _write(full, image.full)
    _write(attachment_path(data_dir, user_id, attachment_id, thumb=True), image.thumb)


def delete_files(data_dir: Path, user_id: str, attachment_id: str) -> None:
    for thumb in (False, True):
        try:
            attachment_path(data_dir, user_id, attachment_id, thumb).unlink(missing_ok=True)
        except OSError as exc:
            log.warning("Could not delete attachment file of %s: %s", attachment_id, exc)


def read_base64(data_dir: Path, user_id: str, attachment_id: str) -> str | None:
    """The stored JPEG as base64 (for the model), or None when the file is gone."""
    try:
        return base64.b64encode(attachment_path(data_dir, user_id, attachment_id).read_bytes()).decode("ascii")
    except OSError as exc:
        log.warning("Attachment %s is missing on disk: %s", attachment_id, exc)
        return None
