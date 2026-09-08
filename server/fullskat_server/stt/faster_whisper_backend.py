"""Local speech-to-text with faster-whisper (optional dependency, extra "whisper")."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import numpy as np

from ..config import Settings
from .base import Transcript, clean_transcript, detect_lang

log = logging.getLogger(__name__)


class FasterWhisperSTT:
    """Runs the model in a worker thread; the model is loaded lazily on first use."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._model: Any = None
        self._load_lock = asyncio.Lock()

    def _load_model(self) -> Any:
        from faster_whisper import WhisperModel  # imported lazily: heavy and optional

        log.info(
            "Loading faster-whisper model %s (device=%s, compute=%s)",
            self._settings.whisper_model,
            self._settings.whisper_device,
            self._settings.whisper_compute_type,
        )
        return WhisperModel(
            self._settings.whisper_model,
            device=self._settings.whisper_device,
            compute_type=self._settings.whisper_compute_type,
        )

    async def _ensure_model(self) -> Any:
        if self._model is None:
            async with self._load_lock:
                if self._model is None:
                    self._model = await asyncio.to_thread(self._load_model)
        return self._model

    def _run(
        self, model: Any, pcm16: bytes, sample_rate: int, lang: str | None, prior_text: str | None, final: bool
    ) -> Transcript:
        audio = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
        if sample_rate != 16000:
            # faster-whisper expects 16 kHz; linear resampling is adequate for speech.
            target = int(len(audio) * 16000 / sample_rate)
            audio = np.interp(
                np.linspace(0, len(audio) - 1, target, dtype=np.float32),
                np.arange(len(audio), dtype=np.float32),
                audio,
            ).astype(np.float32)
        segments, info = model.transcribe(
            audio,
            language=None if lang in (None, "auto") else lang,
            beam_size=5 if final else 1,
            vad_filter=True,
            initial_prompt=prior_text or None,
            condition_on_previous_text=False,
        )
        text = clean_transcript(" ".join(segment.text for segment in segments))
        detected = getattr(info, "language", None)
        return Transcript(text=text, lang=detect_lang(text, lang) or (str(detected) if detected else None))

    async def transcribe(
        self,
        pcm16: bytes,
        sample_rate: int,
        lang: str | None,
        prior_text: str | None,
        *,
        final: bool = False,
    ) -> Transcript:
        model = await self._ensure_model()
        return await asyncio.to_thread(self._run, model, pcm16, sample_rate, lang, prior_text, final)
