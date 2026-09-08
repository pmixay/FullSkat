"""Speech-to-text backends; pick one with STT_BACKEND."""

from __future__ import annotations

import httpx

from ..config import Settings
from .base import STTBackend, Transcript

__all__ = ["STTBackend", "Transcript", "create_stt"]


def create_stt(settings: Settings, client: httpx.AsyncClient) -> STTBackend:
    """Build the backend named by settings.stt_backend. `client` is the shared outbound HTTP client."""
    if settings.stt_backend == "openrouter":
        from .openrouter_backend import OpenRouterSTT

        return OpenRouterSTT(settings, client)
    if settings.stt_backend == "openai":
        from .openai_backend import OpenAITranscriptionSTT

        return OpenAITranscriptionSTT(settings, client)
    from .faster_whisper_backend import FasterWhisperSTT

    return FasterWhisperSTT(settings)
