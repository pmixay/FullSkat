"""Speech-to-text via an audio-capable chat model on OpenRouter."""

from __future__ import annotations

import base64
import logging
from typing import Any

import httpx

from ..config import Settings
from ..llm import REFERER, TITLE, LLMError, error_code_for_status, extract_upstream_message
from .base import Transcript, clean_transcript, detect_lang, pcm_to_wav

log = logging.getLogger(__name__)

_LANG_NAMES = {"ru": "Russian", "en": "English"}


def _instruction(lang: str | None) -> str:
    if lang and lang in _LANG_NAMES:
        language = f"The speech is in {_LANG_NAMES[lang]}."
    else:
        language = "The speech is in Russian or English."
    return (
        "Transcribe the speech in this audio verbatim. "
        f"{language} "
        "Output only the transcript text: no quotes, no labels, no commentary, no translation. "
        "If there is no speech, output nothing."
    )


def _content_text(content: Any) -> str:
    """OpenAI-style content may be a string or a list of parts."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(str(part.get("text", "")) for part in content if isinstance(part, dict))
    return ""


class OpenRouterSTT:
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
        if not self._settings.openrouter_api_key:
            raise LLMError("upstream_auth", "OPENROUTER_API_KEY is not configured on the server")
        audio_b64 = base64.b64encode(pcm_to_wav(pcm16, sample_rate)).decode("ascii")
        body = {
            "model": self._settings.stt_openrouter_model,
            "stream": False,
            "temperature": 0,
            "max_tokens": 600,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": _instruction(lang)},
                        {"type": "input_audio", "input_audio": {"data": audio_b64, "format": "wav"}},
                    ],
                }
            ],
        }
        headers = {
            "Authorization": f"Bearer {self._settings.openrouter_api_key}",
            "HTTP-Referer": REFERER,
            "X-Title": TITLE,
        }
        url = f"{self._settings.openrouter_base_url.rstrip('/')}/chat/completions"
        try:
            response = await self._client.post(url, json=body, headers=headers)
        except httpx.HTTPError as exc:
            raise LLMError("upstream_error", f"could not reach the speech provider: {exc}") from exc
        if response.status_code >= 400:
            message = extract_upstream_message(response.content)
            log.warning("OpenRouter STT returned %s: %s", response.status_code, message)
            raise LLMError(error_code_for_status(response.status_code), message)
        data = response.json()
        if isinstance(data, dict) and data.get("error"):
            raise LLMError("upstream_error", extract_upstream_message(response.content))
        choices = (data.get("choices") or []) if isinstance(data, dict) else []
        raw = _content_text(choices[0].get("message", {}).get("content")) if choices else ""
        text = clean_transcript(raw)
        return Transcript(text=text, lang=detect_lang(text, lang))
