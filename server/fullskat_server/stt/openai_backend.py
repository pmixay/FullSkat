"""Speech-to-text via any OpenAI-compatible /audio/transcriptions endpoint (OpenAI, Groq, ...)."""

from __future__ import annotations

import logging

import httpx

from ..config import Settings
from ..llm import LLMError, error_code_for_status, extract_upstream_message
from .base import Transcript, clean_transcript, detect_lang, pcm_to_wav

log = logging.getLogger(__name__)


class OpenAITranscriptionSTT:
    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client

    async def transcribe(
        self,
        pcm16: bytes,
        sample_rate: int,
        lang: str | None,
        prior_text: str | None,
        *,
        final: bool = False,
    ) -> Transcript:
        if not self._settings.stt_openai_api_key:
            raise LLMError("upstream_auth", "STT_OPENAI_API_KEY is not configured on the server")
        form: dict[str, str] = {"model": self._settings.stt_openai_model, "response_format": "json"}
        if lang and lang != "auto":
            form["language"] = lang
        if prior_text:
            form["prompt"] = prior_text
        files = {"file": ("audio.wav", pcm_to_wav(pcm16, sample_rate), "audio/wav")}
        headers = {"Authorization": f"Bearer {self._settings.stt_openai_api_key}"}
        url = f"{self._settings.stt_openai_base_url.rstrip('/')}/audio/transcriptions"
        try:
            response = await self._client.post(url, data=form, files=files, headers=headers)
        except httpx.HTTPError as exc:
            raise LLMError("upstream_error", f"could not reach the speech provider: {exc}") from exc
        if response.status_code >= 400:
            message = extract_upstream_message(response.content)
            log.warning("Transcription endpoint returned %s: %s", response.status_code, message)
            raise LLMError(error_code_for_status(response.status_code), message)
        data = response.json()
        text = clean_transcript(str(data.get("text", ""))) if isinstance(data, dict) else ""
        detected = data.get("language") if isinstance(data, dict) else None
        return Transcript(text=text, lang=detect_lang(text, lang) or (str(detected) if detected else None))
