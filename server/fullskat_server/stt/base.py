"""Common speech-to-text interface and helpers shared by all backends."""

from __future__ import annotations

import io
import re
import wave
from dataclasses import dataclass
from typing import Protocol

_CYRILLIC = re.compile(r"[Ѐ-ӿ]")
_LATIN = re.compile(r"[A-Za-z]")
_WS = re.compile(r"\s+")
_QUOTES = "\"'«»“”„‘’`"


@dataclass(frozen=True)
class Transcript:
    text: str
    lang: str | None  # ISO 639-1 ("ru", "en") or None when unknown


class STTBackend(Protocol):
    async def transcribe(
        self,
        pcm16: bytes,
        sample_rate: int,
        lang: str | None,
        prior_text: str | None,
        *,
        final: bool = False,
    ) -> Transcript:
        """Transcribe mono 16-bit PCM. `lang` is a hint or None for auto; `prior_text` is the
        previous partial transcript of the same utterance; `final` allows a slower, better pass."""
        ...


def pcm_to_wav(pcm16: bytes, sample_rate: int) -> bytes:
    """Wrap raw mono PCM16 in a WAV container (in memory)."""
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm16)
    return buffer.getvalue()


def clean_transcript(text: str) -> str:
    """Normalize model output: collapse whitespace, strip wrapping quotes and stray labels."""
    cleaned = _WS.sub(" ", text).strip()
    cleaned = cleaned.strip(_QUOTES).strip()
    lowered = cleaned.lower()
    for prefix in ("transcript:", "transcription:", "текст:", "транскрипция:"):
        if lowered.startswith(prefix):
            cleaned = cleaned[len(prefix) :].strip().strip(_QUOTES).strip()
            break
    return cleaned


def detect_lang(text: str, hint: str | None = None) -> str | None:
    """Return the hint when given, otherwise guess ru/en from the Cyrillic-to-Latin letter ratio."""
    if hint and hint != "auto":
        return hint
    cyrillic = len(_CYRILLIC.findall(text))
    latin = len(_LATIN.findall(text))
    if cyrillic == 0 and latin == 0:
        return None
    return "ru" if cyrillic >= latin else "en"
