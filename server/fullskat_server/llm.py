"""Streaming chat through the Claude API, OpenRouter and Kimi; several can be enabled at once."""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

import anthropic
import httpx

from .config import Settings
from .efforts import ModelEfforts, normalize_efforts, pick_default, resolve_effort

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


@dataclass(frozen=True)
class Thinking:
    """A piece of the model's reasoning (summary) text, streamed before or between answer deltas."""

    text: str


class LLMError(Exception):
    """Upstream failure mapped to a protocol error code."""

    def __init__(self, code: str, message: str, retry_after: int | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retry_after = retry_after  # seconds, when the upstream said how long to wait


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


def parse_openrouter_delta(delta: dict[str, Any]) -> list[str | Thinking]:
    """Items carried by one OpenRouter stream delta: reasoning pieces first, then answer text."""
    items: list[str | Thinking] = []
    for key in ("reasoning", "reasoning_content"):
        piece = delta.get(key)
        if isinstance(piece, str) and piece:
            items.append(Thinking(piece))
            break
    text = delta.get("content")
    if isinstance(text, str) and text:
        items.append(text)
    return items


QUOTA_RE = re.compile(
    r"credit balance|insufficient[ _](credits?|funds|balance|quota)|exceeded[ _](your[ _])?(current[ _])?quota|"
    r"out of credits|billing|quota (has been )?(exceeded|exhausted)",
    re.IGNORECASE,
)


def error_code_for_status(status: int, message: str = "") -> str:
    """Protocol error code for an upstream HTTP failure. Spent credits/quota is told apart from a plain rate limit."""
    if status == 402 or (status in (400, 403, 429) and QUOTA_RE.search(message)):
        return "upstream_quota"
    if status in (401, 403):
        return "upstream_auth"
    if status == 429:
        return "upstream_rate_limited"
    return "upstream_error"


def parse_retry_after(value: str | None) -> int | None:
    """Seconds from a Retry-After header (numeric form only), or None."""
    try:
        seconds = int(float(value)) if value else 0
    except ValueError:
        return None
    return seconds if seconds > 0 else None


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


def _web_search_enabled(settings: Settings, requested: bool | None) -> bool:
    """WEB_SEARCH is the master switch: a request can turn search off, never on."""
    return settings.web_search and requested is not False


# How long a provider's answer about which efforts a model supports is trusted.
EFFORTS_TTL = 3600.0
EFFORTS_RETRY = 60.0
# A provider that passed its probe is trusted this long; one that failed is retried sooner.
HEALTH_OK_TTL = 300.0
HEALTH_FAIL_TTL = 30.0
USAGE_TTL = 60.0
PROBE_TIMEOUT = 8.0
# What we assume when OpenRouter's catalog cannot be read: the levels every reasoning model there accepts.
OPENROUTER_FALLBACK_EFFORTS = ("low", "medium", "high", "xhigh")


@dataclass(frozen=True)
class UsageLeft:
    """How much of a provider's budget is left: a 0..1 share for the watch's ring (None = unknown) and a label."""

    remaining: float | None
    label: str = ""

    def to_json(self) -> dict[str, object]:
        share = None if self.remaining is None else round(min(max(self.remaining, 0.0), 1.0), 3)
        return {"remaining": share, "label": self.label}


def _share(left: float, total: float) -> float | None:
    return left / total if total > 0 else None


@dataclass
class _Round:
    """What one streamed chat-completions response added up to."""

    finish_reason: str = ""
    content: str = ""
    reasoning: str = ""
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    prompt_tokens: int = 0
    completion_tokens: int = 0
    has_usage: bool = False


def _merge_tool_calls(calls: dict[int, dict[str, Any]], deltas: Any) -> None:
    """Fold streamed `tool_calls` deltas (arguments arrive in pieces, keyed by index) into whole calls."""
    if not isinstance(deltas, list):
        return
    for delta in deltas:
        if not isinstance(delta, dict):
            continue
        call = calls.setdefault(int(delta.get("index") or 0), {"id": "", "name": "", "arguments": ""})
        if delta.get("id"):
            call["id"] = str(delta["id"])
        function = delta.get("function") or {}
        if function.get("name"):
            call["name"] = str(function["name"])
        if function.get("arguments"):
            call["arguments"] += str(function["arguments"])


class _ChatCompletionsLLM:
    """Shared client for OpenAI-compatible streaming endpoints (OpenRouter, Kimi)."""

    label = "upstream"

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client

    # -- provider hooks --
    def _api_key(self) -> str:
        raise NotImplementedError

    def _url(self) -> str:
        raise NotImplementedError

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self._api_key()}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }

    def _missing_key_message(self) -> str:
        raise NotImplementedError

    def _probe_url(self) -> str:
        raise NotImplementedError

    async def efforts(self, model: str) -> ModelEfforts:
        raise NotImplementedError

    async def probe(self) -> None:
        """Cheap authenticated request that shows the key works. Raises LLMError when it does not."""
        try:
            response = await self._client.get(
                self._probe_url(), headers={"Authorization": f"Bearer {self._api_key()}"}, timeout=PROBE_TIMEOUT
            )
        except httpx.HTTPError as exc:
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
        if response.status_code == 429:
            return  # rate limited, but the key is accepted
        if response.status_code >= 400:
            message = extract_upstream_message(response.content)
            raise LLMError(error_code_for_status(response.status_code, message), message)

    async def aclose(self) -> None:  # the HTTP client is shared and closed by the app
        return None

    # -- streaming --
    async def _round(self, body: dict[str, Any]) -> AsyncIterator[str | Thinking | _Round]:
        """Stream one response: yield text/Thinking items, then a `_Round` summary.

        Raises LLMError for HTTP errors, transport failures and mid-stream error chunks.
        """
        result = _Round()
        tool_calls: dict[int, dict[str, Any]] = {}
        try:
            async with self._client.stream("POST", self._url(), headers=self._headers(), json=body) as response:
                if response.status_code >= 400:
                    message = extract_upstream_message(await response.aread())
                    log.warning("%s returned %s: %s", self.label, response.status_code, message)
                    raise LLMError(
                        error_code_for_status(response.status_code, message),
                        message,
                        parse_retry_after(response.headers.get("retry-after")),
                    )
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
                        message = extract_upstream_message(payload.encode())
                        code = "upstream_quota" if QUOTA_RE.search(message) else "upstream_error"
                        raise LLMError(code, message)
                    usage = chunk.get("usage")
                    choices = chunk.get("choices") or []
                    if choices:
                        choice = choices[0]
                        delta = choice.get("delta") or {}
                        for item in parse_openrouter_delta(delta):
                            if isinstance(item, Thinking):
                                result.reasoning += item.text
                            else:
                                result.content += item
                            yield item
                        _merge_tool_calls(tool_calls, delta.get("tool_calls"))
                        result.finish_reason = choice.get("finish_reason") or result.finish_reason
                        if usage is None:
                            usage = choice.get("usage")  # Moonshot reports usage on the last choice
                    if isinstance(usage, dict):
                        result.has_usage = True
                        result.prompt_tokens = int(usage.get("prompt_tokens") or 0)
                        result.completion_tokens = int(usage.get("completion_tokens") or 0)
        except httpx.HTTPError as exc:
            log.warning("%s request failed: %s", self.label, exc)
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
        result.tool_calls = [tool_calls[i] for i in sorted(tool_calls)]
        yield result

    def _body(
        self, messages: list[dict[str, Any]], model: str, effort: str | None = None, web_search: bool | None = None
    ) -> dict[str, Any]:
        raise NotImplementedError

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Usage]:
        """Yield text deltas (str), Thinking pieces and, if the upstream reports it, a final Usage item.

        `effort` is the final level (already checked against the model); None omits it.
        """
        if not self._api_key():
            raise LLMError("upstream_auth", self._missing_key_message())
        usage_seen = False
        prompt_tokens = completion_tokens = 0
        async for item in self._round(self._body(messages, model, effort, web_search)):
            if isinstance(item, _Round):
                usage_seen, prompt_tokens, completion_tokens = item.has_usage, item.prompt_tokens, item.completion_tokens
            else:
                yield item
        if usage_seen:
            yield Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)


class OpenRouterLLM(_ChatCompletionsLLM):
    """Streams text deltas from OpenRouter's OpenAI-compatible chat endpoint."""

    label = "OpenRouter"

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        super().__init__(settings, client)
        self._catalog: dict[str, ModelEfforts] = {}
        self._catalog_until = 0.0
        self._catalog_lock = asyncio.Lock()

    def _api_key(self) -> str:
        return self._settings.openrouter_api_key

    def _url(self) -> str:
        return f"{self._settings.openrouter_base_url.rstrip('/')}/chat/completions"

    def _missing_key_message(self) -> str:
        return "OPENROUTER_API_KEY is not configured on the server"

    def _probe_url(self) -> str:
        return f"{self._settings.openrouter_base_url.rstrip('/')}/key"

    def _headers(self) -> dict[str, str]:
        return {**super()._headers(), "HTTP-Referer": REFERER, "X-Title": TITLE}

    def _body(
        self, messages: list[dict[str, Any]], model: str, effort: str | None = None, web_search: bool | None = None
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": True,
            "max_tokens": self._settings.openrouter_max_tokens,
            "usage": {"include": True},
        }
        reasoning: dict[str, Any] = {}
        if effort:
            reasoning["effort"] = effort
        if not self._settings.show_thinking:
            reasoning["exclude"] = True  # still reasons, but the summary is not returned or streamed
        if reasoning:
            body["reasoning"] = reasoning
        if _web_search_enabled(self._settings, web_search):
            body["plugins"] = [{"id": "web", "max_results": self._settings.web_search_max_uses}]
        return body

    async def usage(self) -> UsageLeft | None:
        """Credits left: the key's own spending limit if it has one, else the account's total credits."""
        base = self._settings.openrouter_base_url.rstrip("/")
        headers = {"Authorization": f"Bearer {self._api_key()}"}
        try:
            key = (await self._client.get(f"{base}/key", headers=headers, timeout=PROBE_TIMEOUT)).json()["data"]
            limit, left = key.get("limit"), key.get("limit_remaining")
            if limit and left is not None:
                return UsageLeft(_share(float(left), float(limit)), f"${float(left):.2f}")
            credits = (await self._client.get(f"{base}/credits", headers=headers, timeout=PROBE_TIMEOUT)).json()["data"]
            total, used = float(credits["total_credits"]), float(credits["total_usage"])
            return UsageLeft(_share(max(total - used, 0.0), total), f"${max(total - used, 0.0):.2f}")
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            log.debug("OpenRouter usage unavailable: %s", exc)
            return None

    async def _load_catalog(self) -> None:
        """Read which efforts each model accepts from OpenRouter's public model list (cached)."""
        async with self._catalog_lock:
            if time.monotonic() < self._catalog_until:
                return
            url = f"{self._settings.openrouter_base_url.rstrip('/')}/models"
            try:
                response = await self._client.get(url, headers={"Accept": "application/json"}, timeout=10.0)
                response.raise_for_status()
                entries = response.json()["data"]
                self._catalog = {e["id"]: _openrouter_entry_efforts(e, self._settings) for e in entries}
                self._catalog_until = time.monotonic() + EFFORTS_TTL
            except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
                log.warning("Could not read the OpenRouter model list, assuming default efforts: %s", exc)
                self._catalog_until = time.monotonic() + EFFORTS_RETRY

    async def efforts(self, model: str) -> ModelEfforts:
        await self._load_catalog()
        known = self._catalog.get(model)
        if known is not None:
            return known
        supported = OPENROUTER_FALLBACK_EFFORTS
        return ModelEfforts(supported, pick_default(supported, self._settings.openrouter_reasoning_effort))


def _openrouter_entry_efforts(entry: dict[str, Any], settings: Settings) -> ModelEfforts:
    """Efforts of one catalog entry: its `reasoning.supported_efforts`, or none for toggle-only/non-reasoning models."""
    reasoning = entry.get("reasoning")
    if not isinstance(reasoning, dict):
        return ModelEfforts()
    supported = normalize_efforts(reasoning.get("supported_efforts") or [])
    default = pick_default(supported, settings.openrouter_reasoning_effort, reasoning.get("default_effort"))
    return ModelEfforts(supported, default)


class KimiLLM(_ChatCompletionsLLM):
    """Streams from Kimi (Moonshot) and runs its builtin `$web_search` tool."""

    label = "Kimi"
    WEB_SEARCH = "$web_search"

    def _api_key(self) -> str:
        return self._settings.kimi_api_key

    def _url(self) -> str:
        return f"{self._settings.kimi_base_url.rstrip('/')}/chat/completions"

    def _missing_key_message(self) -> str:
        return "KIMI_API_KEY is not configured on the server"

    def _probe_url(self) -> str:
        return f"{self._settings.kimi_base_url.rstrip('/')}/models"

    def _body(
        self, messages: list[dict[str, Any]], model: str, effort: str | None = None, web_search: bool | None = None
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": True,
            "max_completion_tokens": self._settings.kimi_max_tokens,
            "stream_options": {"include_usage": True},
        }
        if effort == "none":
            body["thinking"] = {"type": "disabled"}
        elif effort:
            body["reasoning_effort"] = effort
        if web_search:
            body["tools"] = [{"type": "builtin_function", "function": {"name": self.WEB_SEARCH}}]
        return body

    async def usage(self) -> UsageLeft | None:
        """Account balance; a share for the ring only if KIMI_BALANCE_FULL says what 100% is."""
        url = f"{self._settings.kimi_base_url.rstrip('/')}/users/me/balance"
        try:
            response = await self._client.get(
                url, headers={"Authorization": f"Bearer {self._api_key()}"}, timeout=PROBE_TIMEOUT
            )
            balance = float(response.json()["data"]["available_balance"])
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            log.debug("Kimi balance unavailable: %s", exc)
            return None
        symbol = "$" if "moonshot.ai" in self._settings.kimi_base_url else "¥"
        full = self._settings.kimi_balance_full
        return UsageLeft(_share(balance, full) if full > 0 else None, f"{symbol}{max(balance, 0.0):.2f}")

    async def efforts(self, model: str) -> ModelEfforts:
        supported = normalize_efforts(self._settings.kimi_efforts.split(","))
        return ModelEfforts(supported, pick_default(supported, self._settings.kimi_default_effort))

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Usage]:
        """Like the base stream, but answers the model's `$web_search` calls and asks again.

        Kimi runs the search itself: the client only echoes the call's arguments back as the tool result.
        Each answered call counts against WEB_SEARCH_MAX_USES; when they are used up the tool is withheld.
        """
        if not self._api_key():
            raise LLMError("upstream_auth", self._missing_key_message())
        conversation: list[dict[str, Any]] = list(messages)
        can_search = _web_search_enabled(self._settings, web_search)
        searches = 0
        prompt_tokens = completion_tokens = 0
        usage_seen = False
        while True:
            body = self._body(conversation, model, effort, can_search and searches < self._settings.web_search_max_uses)
            finished = _Round()
            async for item in self._round(body):
                if isinstance(item, _Round):
                    finished = item
                else:
                    yield item
            usage_seen = usage_seen or finished.has_usage
            prompt_tokens += finished.prompt_tokens
            completion_tokens += finished.completion_tokens
            calls = [c for c in finished.tool_calls if c["name"] == self.WEB_SEARCH]
            if finished.finish_reason != "tool_calls" or not calls or searches >= self._settings.web_search_max_uses:
                break
            searches += len(calls)
            assistant: dict[str, Any] = {
                "role": "assistant",
                "content": finished.content,
                "tool_calls": [
                    {"id": c["id"], "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}}
                    for c in calls
                ],
            }
            if finished.reasoning:
                assistant["reasoning_content"] = finished.reasoning  # Kimi wants its reasoning sent back with tool calls
            conversation.append(assistant)
            conversation.extend(
                {"role": "tool", "tool_call_id": c["id"], "name": c["name"], "content": c["arguments"]} for c in calls
            )
        if usage_seen:
            yield Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)


def _supports(capability: Any, level: str) -> bool:
    entry = getattr(capability, level, None)
    return bool(getattr(entry, "supported", False))


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
        self._efforts: dict[str, tuple[float, ModelEfforts]] = {}

    async def probe(self) -> None:
        """Cheap authenticated request that shows the key works. Raises LLMError when it does not."""
        try:
            await self._client.models.list(limit=1)
        except anthropic.APIStatusError as exc:
            if exc.status_code == 429:
                return  # rate limited, but the key is accepted
            message = exc.message or "upstream request failed"
            raise LLMError(error_code_for_status(exc.status_code, message), message) from exc
        except anthropic.APIConnectionError as exc:
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc

    async def usage(self) -> UsageLeft | None:
        """Share of the token rate limit left, from the response headers (Anthropic has no balance endpoint)."""
        try:
            raw = await self._client.with_raw_response.models.list(limit=1)
            headers = raw.headers
            left = float(headers["anthropic-ratelimit-tokens-remaining"])
            total = float(headers["anthropic-ratelimit-tokens-limit"])
        except (anthropic.APIError, KeyError, ValueError, TypeError, AttributeError) as exc:
            log.debug("Anthropic rate-limit headers unavailable: %s", exc)
            return None
        return UsageLeft(_share(left, total), "")

    async def efforts(self, model: str) -> ModelEfforts:
        """Efforts from the Models API capabilities (cached); a static set if it cannot be read."""
        cached = self._efforts.get(model)
        if cached and time.monotonic() < cached[0]:
            return cached[1]
        ttl = EFFORTS_TTL
        try:
            effort = (await self._client.models.retrieve(model)).capabilities.effort
            levels = [name for name in ("low", "medium", "high", "xhigh", "max") if _supports(effort, name)]
            supported = normalize_efforts(levels) if effort.supported else ()
        except Exception as exc:  # the capability lookup is best effort and must never break chat
            log.debug("Could not read effort capabilities of %s: %s", model, exc)
            supported = ("low", "medium", "high", "max")
            ttl = EFFORTS_RETRY
        info = ModelEfforts(supported, pick_default(supported, self._settings.anthropic_effort))
        self._efforts[model] = (time.monotonic() + ttl, info)
        return info

    async def aclose(self) -> None:
        await self._client.close()

    def _request(
        self,
        system: str,
        conversation: list[dict[str, Any]],
        model: str,
        effort: str | None = None,
        web_search: bool | None = None,
    ) -> dict[str, Any]:
        request: dict[str, Any] = {
            "model": model,
            "max_tokens": self._settings.anthropic_max_tokens,
            "system": system,
            "messages": conversation,
        }
        if self._settings.show_thinking:
            request["thinking"] = {"type": "adaptive", "display": "summarized"}
        if effort:
            request["output_config"] = {"effort": effort}
        if _web_search_enabled(self._settings, web_search):
            request["tools"] = [
                {"type": WEB_SEARCH_TOOL, "name": "web_search", "max_uses": self._settings.web_search_max_uses}
            ]
        return request

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Usage]:
        """Yield text deltas (str), Thinking pieces and a final Usage item. Raises LLMError like OpenRouterLLM."""
        if not self._settings.anthropic_api_key:
            raise LLMError("upstream_auth", "ANTHROPIC_API_KEY is not configured on the server")
        system, conversation = _anthropic_conversation(messages)
        if not conversation:
            raise LLMError("upstream_error", "no user message to answer")
        request = self._request(system, conversation, model, effort, web_search)
        prompt_tokens = completion_tokens = 0
        try:
            for _ in range(MAX_PAUSE_TURNS):
                async with self._client.messages.stream(**request) as stream:
                    async for event in stream:
                        if event.type != "content_block_delta":
                            continue
                        delta = event.delta
                        if delta.type == "thinking_delta" and delta.thinking:
                            yield Thinking(delta.thinking)
                        elif delta.type == "text_delta" and delta.text:
                            yield delta.text
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
            message = exc.message or "upstream request failed"
            retry_after = parse_retry_after(exc.response.headers.get("retry-after"))
            raise LLMError(error_code_for_status(exc.status_code, message), message, retry_after) from exc
        except anthropic.APIConnectionError as exc:
            log.warning("Anthropic request failed: %s", exc)
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
        yield Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)


Provider = AnthropicLLM | OpenRouterLLM | KimiLLM


@dataclass(frozen=True)
class Health:
    """Outcome of the last probe of one provider."""

    ok: bool
    reason: str = ""  # LLMError code of the failure ("upstream_auth", ...), "" when ok
    message: str = ""
    until: float = 0.0  # monotonic time after which it is probed again


class MultiLLM:
    """Routes each model to the provider that serves it and offers only providers that currently work.

    A provider is a candidate when its API key is set; it is enabled while its probe succeeds. Probes are
    cached (HEALTH_OK_TTL / HEALTH_FAIL_TTL), and an auth failure during a chat disables the provider at once.
    """

    def __init__(self, settings: Settings, providers: dict[str, Provider]) -> None:
        self._settings = settings
        self.providers = providers
        self._health: dict[str, Health] = {}
        self._probe_lock = asyncio.Lock()
        self._usage: dict[str, tuple[float, UsageLeft | None]] = {}
        self._usage_lock = asyncio.Lock()

    # -- diagnosis --
    async def _probe(self, name: str) -> Health:
        try:
            await asyncio.wait_for(self.providers[name].probe(), PROBE_TIMEOUT + 2)
        except LLMError as exc:
            log.warning("Provider %s is unavailable: %s (%s)", name, exc.message, exc.code)
            return Health(False, exc.code, exc.message, time.monotonic() + HEALTH_FAIL_TTL)
        except Exception as exc:  # a broken probe must not take chat down
            log.warning("Provider %s probe failed: %s", name, exc)
            return Health(False, "upstream_error", str(exc), time.monotonic() + HEALTH_FAIL_TTL)
        return Health(True, until=time.monotonic() + HEALTH_OK_TTL)

    async def refresh(self) -> None:
        """Probe the providers whose last result has expired (all of them on the first call)."""
        async with self._probe_lock:
            now = time.monotonic()
            stale = [n for n in self.providers if n not in self._health or self._health[n].until <= now]
            for name, health in zip(stale, await asyncio.gather(*(self._probe(n) for n in stale))):
                self._health[name] = health

    async def usage(self) -> dict[str, UsageLeft | None]:
        """Budget left per working provider (cached for USAGE_TTL); a failed lookup is just unknown."""
        async with self._usage_lock:
            now = time.monotonic()
            stale = [n for n in await self.working_providers() if n not in self._usage or self._usage[n][0] <= now]

            async def fetch(name: str) -> UsageLeft | None:
                try:
                    return await asyncio.wait_for(self.providers[name].usage(), PROBE_TIMEOUT + 2)
                except Exception as exc:
                    log.debug("Usage of %s unavailable: %s", name, exc)
                    return None

            for name, value in zip(stale, await asyncio.gather(*(fetch(n) for n in stale))):
                self._usage[name] = (time.monotonic() + USAGE_TTL, value)
            return {n: v for n, (_, v) in self._usage.items()}

    async def providers_info(self) -> dict[str, dict[str, Any]]:
        """Diagnosis of every configured provider with its models and budget left, for the watch."""
        await self.refresh()
        usage = await self.usage()
        info: dict[str, dict[str, Any]] = {}
        for name in self.providers:
            health = self._health[name]
            mine = usage.get(name) if health.ok else None
            info[name] = {
                "ok": health.ok,
                "reason": health.reason,
                "models": [m for m in self._settings.provider_models(name) if self._settings.provider_for(m) == name]
                if health.ok
                else [],
                "usage": mine.to_json() if mine else None,
            }
        return info

    def mark_failed(self, name: str, error: LLMError) -> None:
        self._health[name] = Health(False, error.code, error.message, time.monotonic() + HEALTH_FAIL_TTL)

    async def working_providers(self) -> list[str]:
        await self.refresh()
        return [n for n in self.providers if self._health[n].ok]

    # -- models --
    async def models(self) -> tuple[str, list[str]]:
        """(default model, all models) of the working providers; the first working provider sets the default."""
        working = await self.working_providers()
        if not working:
            return "", []
        models: list[str] = []
        for name in working:
            models.extend(m for m in self._settings.provider_models(name) if m not in models)
        return self._settings.provider_default_model(working[0]), models

    def _provider(self, model: str) -> tuple[str, Provider]:
        name = self._settings.provider_for(model)
        if name is None or name not in self.providers:
            raise LLMError("upstream_error", f"no provider is configured for model {model!r}")
        return name, self.providers[name]

    async def efforts(self, model: str) -> ModelEfforts:
        """Efforts the model supports; unknown models and failed lookups mean "not adjustable"."""
        try:
            return await self._provider(model)[1].efforts(model)
        except LLMError:
            return ModelEfforts()
        except Exception:
            log.exception("effort lookup failed for %s", model)
            return ModelEfforts()

    async def model_efforts(self, models: list[str]) -> dict[str, ModelEfforts]:
        return dict(zip(models, await asyncio.gather(*(self.efforts(m) for m in models))))

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Usage]:
        """Pick the final effort for the model (requested, else its default, clamped to what it supports)."""
        name, provider = self._provider(model)
        if name not in await self.working_providers():
            health = self._health[name]
            raise LLMError(health.reason or "upstream_error", health.message or f"provider {name} is unavailable")
        info = await self.efforts(model)
        chosen = resolve_effort(effort, info.efforts) or info.default or None
        try:
            async for item in provider.stream(messages, model, effort=chosen, web_search=web_search):
                yield item
        except LLMError as exc:
            if exc.code == "upstream_auth":
                self.mark_failed(name, exc)  # stop offering the provider's models until a probe passes again
            raise

    async def aclose(self) -> None:
        for provider in self.providers.values():
            if isinstance(provider, AnthropicLLM):
                await provider.aclose()


def create_llm(settings: Settings, client: httpx.AsyncClient) -> MultiLLM:
    """Build a client for every provider that has an API key. `client` is the shared outbound HTTP client."""
    providers: dict[str, Provider] = {}
    for name in settings.configured_providers:
        if name == "anthropic":
            providers[name] = AnthropicLLM(settings)
        elif name == "openrouter":
            providers[name] = OpenRouterLLM(settings, client)
        else:
            providers[name] = KimiLLM(settings, client)
    return MultiLLM(settings, providers)
