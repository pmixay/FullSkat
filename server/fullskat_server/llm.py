"""Streaming chat completions through the Claude API (default) or OpenRouter."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, AsyncIterator

import anthropic
import httpx

from .config import Settings

log = logging.getLogger(__name__)

REFERER = "https://github.com/pmixay/FullSkat"
TITLE = "FullSkat"

# Basic server-side web search: works on every Claude model, no beta header.
WEB_SEARCH_TOOL = "web_search_20250305"
# A server-side tool loop that hits its iteration cap ends with pause_turn; resume it a few times at most.
MAX_PAUSE_TURNS = 4


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
        if self._settings.web_search:
            body["plugins"] = [{"id": "web", "max_results": self._settings.web_search_max_uses}]
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


def _anthropic_conversation(messages: list[dict[str, str]]) -> tuple[str, list[dict[str, str]]]:
    """Split OpenAI-style messages into the system prompt and a conversation that starts with a user turn."""
    system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
    conversation = [{"role": m["role"], "content": m["content"]} for m in messages if m["role"] != "system"]
    while conversation and conversation[0]["role"] != "user":
        conversation.pop(0)
    return system, conversation


class AnthropicLLM:
    """Streams text deltas from the Claude API through the official SDK."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        options: dict[str, Any] = {"timeout": anthropic.Timeout(120.0, connect=10.0)}
        if settings.anthropic_api_key:
            options["api_key"] = settings.anthropic_api_key
        if settings.anthropic_base_url:
            options["base_url"] = settings.anthropic_base_url
        self._client = anthropic.AsyncAnthropic(**options)

    async def aclose(self) -> None:
        await self._client.close()

    def _request(self, system: str, conversation: list[dict[str, Any]], model: str) -> dict[str, Any]:
        request: dict[str, Any] = {
            "model": model,
            "max_tokens": self._settings.anthropic_max_tokens,
            "system": system,
            "messages": conversation,
        }
        if self._settings.anthropic_effort:
            request["output_config"] = {"effort": self._settings.anthropic_effort}
        if self._settings.web_search:
            request["tools"] = [
                {"type": WEB_SEARCH_TOOL, "name": "web_search", "max_uses": self._settings.web_search_max_uses}
            ]
        return request

    async def stream(self, messages: list[dict[str, str]], model: str) -> AsyncIterator[str | Usage]:
        """Yield text deltas (str) and a final Usage item. Raises LLMError like OpenRouterLLM."""
        if not self._settings.anthropic_api_key:
            raise LLMError("upstream_auth", "ANTHROPIC_API_KEY is not configured on the server")
        system, conversation = _anthropic_conversation(messages)
        if not conversation:
            raise LLMError("upstream_error", "no user message to answer")
        request = self._request(system, conversation, model)
        prompt_tokens = completion_tokens = 0
        try:
            for _ in range(MAX_PAUSE_TURNS):
                async with self._client.messages.stream(**request) as stream:
                    async for text in stream.text_stream:
                        yield text
                    final = await stream.get_final_message()
                usage = final.usage
                prompt_tokens += (
                    usage.input_tokens
                    + (usage.cache_read_input_tokens or 0)
                    + (usage.cache_creation_input_tokens or 0)
                )
                completion_tokens += usage.output_tokens
                if final.stop_reason == "refusal":
                    raise LLMError("upstream_error", "the model declined to answer this request")
                if final.stop_reason != "pause_turn":
                    break
                # Resume the paused server-side tool loop: send the assistant turn back as it is.
                request["messages"] = [*request["messages"], {"role": "assistant", "content": final.content}]
        except anthropic.APIStatusError as exc:
            log.warning("Anthropic returned %s: %s", exc.status_code, exc.message)
            raise LLMError(error_code_for_status(exc.status_code), exc.message or "upstream request failed") from exc
        except anthropic.APIConnectionError as exc:
            log.warning("Anthropic request failed: %s", exc)
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
        yield Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)


def create_llm(settings: Settings, client: httpx.AsyncClient) -> AnthropicLLM | OpenRouterLLM:
    """Build the chat provider named by settings.llm_provider. `client` is the shared outbound HTTP client."""
    if settings.llm_provider == "anthropic":
        return AnthropicLLM(settings)
    return OpenRouterLLM(settings, client)
