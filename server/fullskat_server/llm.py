"""Streaming chat completions through OpenRouter."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, AsyncIterator

import httpx

from .config import Settings

log = logging.getLogger(__name__)

REFERER = "https://github.com/pmixay/FullSkat"
TITLE = "FullSkat"


@dataclass(frozen=True)
class Usage:
    prompt_tokens: int
    completion_tokens: int

    def to_json(self) -> dict[str, int]:
        return {"prompt_tokens": self.prompt_tokens, "completion_tokens": self.completion_tokens}


class LLMError(Exception):
    """Upstream failure mapped to a protocol error code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def trim_history(messages: list[dict[str, str]], max_messages: int, max_chars: int) -> list[dict[str, str]]:
    """Keep the newest messages within both the count and the total-character budget.

    The newest message is always kept (truncated if it alone exceeds the budget).
    """
    kept = messages[-max_messages:]
    total = sum(len(m["content"]) for m in kept)
    while len(kept) > 1 and total > max_chars:
        total -= len(kept[0]["content"])
        kept = kept[1:]
    if kept and len(kept[0]["content"]) > max_chars:
        kept = [{**kept[0], "content": kept[0]["content"][:max_chars]}]
    return kept


def error_code_for_status(status: int) -> str:
    if status in (401, 403):
        return "upstream_auth"
    if status == 429:
        return "upstream_rate_limited"
    return "upstream_error"


def extract_upstream_message(body: bytes) -> str:
    try:
        data = json.loads(body)
    except ValueError:
        return body[:200].decode("utf-8", errors="replace") or "upstream request failed"
    error = data.get("error") if isinstance(data, dict) else None
    if isinstance(error, dict) and error.get("message"):
        return str(error["message"])
    if isinstance(error, str):
        return error
    return "upstream request failed"


class OpenRouterLLM:
    """Streams text deltas from OpenRouter's OpenAI-compatible chat endpoint."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self._settings.openrouter_api_key}",
            "HTTP-Referer": REFERER,
            "X-Title": TITLE,
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }

    def _body(self, messages: list[dict[str, str]], model: str) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": True,
            "max_tokens": self._settings.max_completion_tokens,
            "usage": {"include": True},
        }
        effort = self._settings.reasoning_effort
        if effort:
            body["reasoning"] = {"effort": effort}
        return body

    async def stream(self, messages: list[dict[str, str]], model: str) -> AsyncIterator[str | Usage]:
        """Yield text deltas (str) and, if the upstream reports it, a final Usage item.

        Raises LLMError for HTTP errors, transport failures and mid-stream error chunks.
        """
        if not self._settings.openrouter_api_key:
            raise LLMError("upstream_auth", "OPENROUTER_API_KEY is not configured on the server")
        url = f"{self._settings.openrouter_base_url.rstrip('/')}/chat/completions"
        try:
            async with self._client.stream(
                "POST", url, headers=self._headers(), json=self._body(messages, model)
            ) as response:
                if response.status_code >= 400:
                    body = await response.aread()
                    message = extract_upstream_message(body)
                    log.warning("OpenRouter returned %s: %s", response.status_code, message)
                    raise LLMError(error_code_for_status(response.status_code), message)
                async for line in response.aiter_lines():
                    line = line.strip()
                    if not line or line.startswith(":"):
                        continue  # keep-alive comments such as ": OPENROUTER PROCESSING"
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        chunk = json.loads(payload)
                    except ValueError:
                        log.debug("Skipping malformed SSE payload: %r", payload[:120])
                        continue
                    if not isinstance(chunk, dict):
                        continue
                    if chunk.get("error"):
                        raise LLMError("upstream_error", extract_upstream_message(payload.encode()))
                    choices = chunk.get("choices") or []
                    if choices:
                        delta = choices[0].get("delta") or {}
                        text = delta.get("content")
                        if text:
                            yield text
                    usage = chunk.get("usage")
                    if isinstance(usage, dict):
                        yield Usage(
                            prompt_tokens=int(usage.get("prompt_tokens") or 0),
                            completion_tokens=int(usage.get("completion_tokens") or 0),
                        )
        except httpx.HTTPError as exc:
            log.warning("OpenRouter request failed: %s", exc)
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
