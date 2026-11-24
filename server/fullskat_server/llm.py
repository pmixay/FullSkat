"""Streaming chat through Claude, OpenRouter, Kimi and OpenAI Responses gateways."""

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
from .figures import DRAW_TOOL, FigureError, anthropic_tool, chat_completions_tool, normalize_figure, responses_tool
from .sources import SourceCollector

log = logging.getLogger(__name__)

REFERER = "https://github.com/pmixay/FullSkat"
# Kimi Code asks clients to keep their real identifier; this is ours.
USER_AGENT = "FullSkat/1.0 (+https://github.com/pmixay/FullSkat)"
TITLE = "FullSkat"

# Basic server-side web search: works on every Claude model, no beta header.
WEB_SEARCH_TOOL = "web_search_20250305"
# A server-side tool loop that hits its iteration cap ends with pause_turn; resume it a few times at most.
MAX_PAUSE_TURNS = 4
# Requests one answer may take in all: tool calls the server answers (draw, Kimi's search) plus pause_turn resumes.
MAX_TOOL_ROUNDS = 8


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


@dataclass(frozen=True)
class Figure:
    """A checked `draw` call (see figures.py), streamed to the watch inside the answer where the model drew it."""

    figure: dict[str, Any]


def answer_draw(arguments: Any) -> tuple[Figure | None, str]:
    """Run one `draw` call: the figure to show (None if it cannot be drawn) and the tool result for the model."""
    try:
        figure, note = normalize_figure(arguments)
    except FigureError as exc:
        return None, f"Not drawn: {exc}. Fix the call or answer in words."
    return Figure(figure), note


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


def _figures_enabled(settings: Settings, requested: bool | None) -> bool:
    """FIGURES is the master switch for the draw tool, like WEB_SEARCH: a request can turn it off, never on."""
    return settings.figures and requested is not False


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
    # HTTP statuses of the probe that still mean "the key is accepted" (a rate limit, or an endpoint the vendor lacks).
    probe_ok_statuses: tuple[int, ...] = (429,)
    # Name of a search tool whose calls the client answers by echoing the arguments (Kimi's builtin), "" if none.
    SEARCH_TOOL = ""

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
            "User-Agent": USER_AGENT,
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
                self._probe_url(),
                headers={"Authorization": f"Bearer {self._api_key()}", "User-Agent": USER_AGENT},
                timeout=PROBE_TIMEOUT,
            )
        except httpx.HTTPError as exc:
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
        if response.status_code in self.probe_ok_statuses:
            return
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
        sources = SourceCollector()
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
                        sources.add_chat_choice(choice)
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
        if sources:
            yield sources.result()
        yield result

    def _body(
        self,
        messages: list[dict[str, Any]],
        model: str,
        effort: str | None = None,
        web_search: bool | None = None,
        draw: str = "",
    ) -> dict[str, Any]:
        """One request. `draw` offers the draw tool: "auto", "none" (calls used up, may not call it) or "" (off)."""
        raise NotImplementedError

    def _search_arg(self, web_search: bool | None, searches: int, first_round: bool) -> bool | None:
        """The `web_search` argument of `_body` for this round (provider specific)."""
        return web_search

    def _assistant_extras(self, finished: _Round) -> dict[str, Any]:
        """Extra fields of an assistant turn with tool calls that is sent back (Kimi wants its reasoning)."""
        return {}

    def _tool_message(self, call: dict[str, Any], content: str) -> dict[str, Any]:
        return {"role": "tool", "tool_call_id": call["id"], "content": content}

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
        figures: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Figure | Usage]:
        """Yield text deltas (str), Thinking pieces, Figures and, if the upstream reports it, a final Usage item.

        `effort` is the final level (already checked against the model); None omits it. Tool calls the server
        answers itself (draw; Kimi's search) are answered and the model is asked again, at most MAX_TOOL_ROUNDS times.
        """
        if not self._api_key():
            raise LLMError("upstream_auth", self._missing_key_message())
        conversation: list[dict[str, Any]] = list(messages)
        can_draw = _figures_enabled(self._settings, figures)
        searches = drawn = 0
        prompt_tokens = completion_tokens = 0
        usage_seen = False
        for round_index in range(MAX_TOOL_ROUNDS):
            search = self._search_arg(web_search, searches, round_index == 0)
            draw = "" if not can_draw else ("auto" if drawn < self._settings.figures_max_per_answer else "none")
            body = self._body(conversation, model, effort, search, draw)
            finished = _Round()
            try:
                async for item in self._round(body):
                    if isinstance(item, _Round):
                        finished = item
                    else:
                        yield item
            except LLMError as exc:
                if self._drop_search(body, exc, finished):
                    continue
                raise
            usage_seen = usage_seen or finished.has_usage
            prompt_tokens += finished.prompt_tokens
            completion_tokens += finished.completion_tokens
            if finished.finish_reason not in ("tool_calls", "function_call") or not finished.tool_calls:
                break
            replies: list[dict[str, Any]] = []
            for number, call in enumerate(finished.tool_calls):
                call["id"] = call["id"] or f"call_{round_index}_{number}"
                if call["name"] == DRAW_TOOL and can_draw:
                    drawn += 1
                    figure, note = answer_draw(call["arguments"])
                    if figure is not None:
                        yield figure
                    replies.append(self._tool_message(call, note))
                elif self.SEARCH_TOOL and call["name"] == self.SEARCH_TOOL:
                    searches += 1  # the provider runs the search itself: the client only echoes the arguments back
                    replies.append(self._tool_message(call, call["arguments"]))
                else:
                    break  # a tool we do not run: keep what was said
            if len(replies) != len(finished.tool_calls):
                break
            conversation.append({
                "role": "assistant",
                "content": finished.content,
                "tool_calls": [
                    {"id": c["id"], "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}}
                    for c in finished.tool_calls
                ],
                **self._assistant_extras(finished),
            })
            conversation.extend(replies)
        if usage_seen:
            yield Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)

    def _drop_search(self, body: dict[str, Any], exc: LLMError, finished: _Round) -> bool:
        """Whether a failed round should be retried without the search tool (Kimi); False re-raises the error."""
        return False


class EffortCatalog:
    """OpenRouter's public model list: which efforts each model accepts (cached, no API key needed)."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client
        self._entries: dict[str, tuple[tuple[str, ...], str]] = {}
        self._until = 0.0
        self._lock = asyncio.Lock()

    async def _load(self) -> None:
        async with self._lock:
            if time.monotonic() < self._until:
                return
            url = f"{self._settings.openrouter_base_url.rstrip('/')}/models"
            try:
                response = await self._client.get(url, headers={"Accept": "application/json"}, timeout=10.0)
                response.raise_for_status()
                self._entries = {e["id"]: _entry_efforts(e) for e in response.json()["data"]}
                self._until = time.monotonic() + EFFORTS_TTL
            except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
                log.warning("Could not read the OpenRouter model list, assuming default efforts: %s", exc)
                self._until = time.monotonic() + EFFORTS_RETRY

    async def lookup(self, model_id: str) -> tuple[tuple[str, ...], str] | None:
        """(supported efforts, the catalog's default) of a model, or None when it is unknown or the list is unreadable.

        An empty tuple means the model is known but its effort is not adjustable (reasoning on/off or none).
        """
        await self._load()
        return self._entries.get(model_id)


def _entry_efforts(entry: dict[str, Any]) -> tuple[tuple[str, ...], str]:
    """Efforts of one catalog entry: its `reasoning.supported_efforts`, none for toggle-only/non-reasoning models."""
    reasoning = entry.get("reasoning")
    if not isinstance(reasoning, dict):
        return (), ""
    return normalize_efforts(reasoning.get("supported_efforts") or []), str(reasoning.get("default_effort") or "")


class OpenRouterLLM(_ChatCompletionsLLM):
    """Streams text deltas from OpenRouter's OpenAI-compatible chat endpoint."""

    label = "OpenRouter"

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        super().__init__(settings, client)
        self._catalog = EffortCatalog(settings, client)

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
        self,
        messages: list[dict[str, Any]],
        model: str,
        effort: str | None = None,
        web_search: bool | None = None,
        draw: str = "",
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": True,
            "max_tokens": self._settings.openrouter_max_tokens,
            "usage": {"include": True},
        }
        tools: list[dict[str, Any]] = []
        reasoning: dict[str, Any] = {}
        if effort:
            reasoning["effort"] = effort
        if not self._settings.show_thinking:
            reasoning["exclude"] = True  # still reasons, but the summary is not returned or streamed
        if reasoning:
            body["reasoning"] = reasoning
        if _web_search_enabled(self._settings, web_search):
            # The `web` plugin (searches once on every request) is deprecated; with the server tool the model
            # decides whether to search, at most WEB_SEARCH_MAX_USES times (enforced by OpenRouter's own engines).
            tools.append(
                {"type": "openrouter:web_search", "parameters": {"max_uses": self._settings.web_search_max_uses}}
            )
        if draw:
            tools.append(chat_completions_tool())
            if draw == "none":
                body["tool_choice"] = "none"
        if tools:
            body["tools"] = tools
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

    async def efforts(self, model: str) -> ModelEfforts:
        known = await self._catalog.lookup(model)
        if known is not None:
            supported, catalog_default = known
            return ModelEfforts(supported, pick_default(supported, self._settings.openrouter_reasoning_effort, catalog_default))
        supported = OPENROUTER_FALLBACK_EFFORTS
        return ModelEfforts(supported, pick_default(supported, self._settings.openrouter_reasoning_effort))


class OpenAILLM(_ChatCompletionsLLM):
    """OpenAI Responses streams, including Codex LB's /v1 compatibility endpoint."""

    label = "OpenAI"

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        super().__init__(settings, client)
        self._models: dict[str, dict[str, Any]] = {}

    def _api_key(self) -> str:
        return self._settings.openai_api_key

    def _url(self) -> str:
        return f"{self._settings.openai_base_url.rstrip('/')}/responses"

    def _probe_url(self) -> str:
        return f"{self._settings.openai_base_url.rstrip('/')}/models"

    async def probe(self) -> None:
        try:
            response = await self._client.get(self._probe_url(), headers=self._headers(), timeout=PROBE_TIMEOUT)
            if response.status_code >= 400:
                message = extract_upstream_message(response.content)
                raise LLMError(error_code_for_status(response.status_code, message), message)
            entries = response.json()["data"]
            if not isinstance(entries, list):
                raise ValueError("invalid model list")
            self._models = {entry["id"]: entry.get("metadata") or {} for entry in entries}
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise LLMError("upstream_error", "could not read the OpenAI model catalog") from exc

    async def efforts(self, model: str) -> ModelEfforts:
        override = self._settings.openai_model_efforts.get(model)
        metadata = self._models.get(model, {})
        supported = normalize_efforts(
            override.split(",") if override is not None else
            [level.get("effort") for level in metadata.get("supported_reasoning_levels", [])]
        )
        return ModelEfforts(supported, pick_default(
            supported, self._settings.openai_reasoning_effort, metadata.get("default_reasoning_level")
        ))

    async def usage(self) -> UsageLeft | None:
        # Codex LB exposes quota for this key without granting dashboard access.
        try:
            response = await self._client.get(
                f"{self._settings.openai_base_url.rstrip('/')}/usage", headers=self._headers(), timeout=PROBE_TIMEOUT
            )
            if response.status_code != 200:
                return None
            data = response.json()
            limits = data.get("limits") or []
            shares = [float(limit["remaining_value"]) / float(limit["max_value"])
                      for limit in limits if not limit.get("model_filter") and float(limit.get("max_value") or 0) > 0]
            if shares:
                left = min(shares)
                return UsageLeft(left, f"{100 * left:.0f}%")
        except (httpx.HTTPError, ValueError, TypeError, KeyError):
            pass
        return None

    def _body(
        self,
        messages: list[dict[str, Any]],
        model: str,
        effort: str | None = None,
        web_search: bool | None = None,
        draw: str = "",
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "instructions": "\n\n".join(m["content"] for m in messages if m.get("role") == "system"),
            "input": [m for m in messages if m.get("role") != "system"],
            "stream": True,
            "store": False,
            "max_output_tokens": self._settings.openai_max_tokens,
        }
        reasoning: dict[str, str] = {}
        if effort:
            reasoning["effort"] = effort
        if self._settings.show_thinking:
            reasoning["summary"] = "auto"
        if reasoning:
            body["reasoning"] = reasoning
        tools: list[dict[str, Any]] = []
        if _web_search_enabled(self._settings, web_search):
            tools.append({"type": "web_search"})
            body["include"] = ["web_search_call.action.sources"]  # pages consulted, for the watch's source list
        if draw:
            tools.append(responses_tool())
            if draw == "none":
                body["tool_choice"] = "none"
        if tools:
            body["tools"] = tools
        return body

    async def stream(
        self, messages: list[dict[str, str]], model: str, *,
        effort: str | None = None, web_search: bool | None = None, figures: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Figure | Usage]:
        """Translate Responses events to watch items. A response that ends in draw calls is answered and continued:
        the follow-up input repeats the conversation plus the text so far, the calls and their outputs (store=false,
        so nothing can be referenced by id)."""
        if not self._api_key().strip():
            raise LLMError("upstream_auth", "OPENAI_API_KEY is not configured on the server")
        conversation: list[dict[str, Any]] = list(messages)
        can_draw = _figures_enabled(self._settings, figures)
        drawn = input_tokens = output_tokens = 0
        for _ in range(MAX_TOOL_ROUNDS):
            draw = "" if not can_draw else ("auto" if drawn < self._settings.figures_max_per_answer else "none")
            finished = _ResponseRound()
            async for item in self._response_round(self._body(conversation, model, effort, web_search, draw)):
                if isinstance(item, _ResponseRound):
                    finished = item
                else:
                    yield item
            input_tokens += finished.input_tokens
            output_tokens += finished.output_tokens
            calls = [c for c in finished.calls if c.get("name") == DRAW_TOOL] if can_draw else []
            if not calls:
                break
            if finished.text:
                conversation.append({"role": "assistant", "content": finished.text})
            for call in calls:
                drawn += 1
                figure, note = answer_draw(call.get("arguments"))
                if figure is not None:
                    yield figure
                call_id = str(call.get("call_id") or "")
                conversation.append({"type": "function_call", "call_id": call_id, "name": DRAW_TOOL,
                                     "arguments": str(call.get("arguments") or "")})
                conversation.append({"type": "function_call_output", "call_id": call_id, "output": note})
        yield Usage(input_tokens, output_tokens)

    async def _response_round(self, body: dict[str, Any]) -> AsyncIterator[str | Thinking | _ResponseRound]:
        """Stream one response: text/Thinking items, then a `_ResponseRound` with its function calls and usage."""
        result = _ResponseRound()
        try:
            async with self._client.stream("POST", self._url(), headers=self._headers(), json=body) as response:
                if response.status_code >= 400:
                    message = extract_upstream_message(await response.aread())
                    raise LLMError(error_code_for_status(response.status_code, message), message,
                                   parse_retry_after(response.headers.get("retry-after")))
                sources = SourceCollector()
                async for event in _response_events(response):
                    sources.add_responses_event(event)
                    kind = event.get("type")
                    if kind in ("response.output_text.delta", "response.refusal.delta"):
                        if event.get("delta"):
                            result.text += str(event["delta"])
                            yield str(event["delta"])
                    elif kind == "response.reasoning_summary_text.delta":
                        if self._settings.show_thinking and event.get("delta"):
                            yield Thinking(str(event["delta"]))
                    elif kind == "response.output_item.done":
                        item = event.get("item") or {}
                        if item.get("type") == "function_call":
                            result.add_call(item)
                    elif kind == "response.completed":
                        if sources:
                            yield sources.result()
                        done = event.get("response") or {}
                        usage = done.get("usage") or {}
                        result.input_tokens = int(usage.get("input_tokens") or 0)
                        result.output_tokens = int(usage.get("output_tokens") or 0)
                        for item in done.get("output") or []:
                            if isinstance(item, dict) and item.get("type") == "function_call":
                                result.add_call(item)
                        yield result
                        return
                    elif kind in ("error", "response.failed", "response.incomplete"):
                        failed = event.get("response") or event
                        error = failed.get("error") or failed.get("incomplete_details") or failed
                        message = str(error.get("message") or error.get("reason") or "OpenAI response failed")
                        code = str(error.get("code") or "")
                        status = {"invalid_api_key": 401, "authentication_error": 401,
                                  "rate_limit_exceeded": 429, "usage_limit_reached": 429,
                                  "insufficient_quota": 402}.get(code, 500)
                        raise LLMError(error_code_for_status(status, f"{code} {message}"), message,
                                       parse_retry_after(str(error.get("retry_after_seconds") or "")))
        except httpx.HTTPError as exc:
            raise LLMError("upstream_error", "could not reach the OpenAI gateway") from exc
        except (ValueError, TypeError, AttributeError) as exc:
            raise LLMError("upstream_error", "invalid OpenAI response stream") from exc
        # Never report a truncated stream as a successful watch answer.
        raise LLMError("upstream_error", "OpenAI stream ended before response.completed")


@dataclass
class _ResponseRound:
    """What one Responses stream added up to: its text, function calls (unique by call_id) and token usage."""

    text: str = ""
    calls: list[dict[str, Any]] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0

    def add_call(self, item: dict[str, Any]) -> None:
        if not any(c.get("call_id") == item.get("call_id") for c in self.calls):
            self.calls.append(item)


async def _response_events(response: httpx.Response) -> AsyncIterator[dict[str, Any]]:
    """Read SSE data frames, including multiline JSON and a final frame without a blank line."""
    data: list[str] = []
    async for line in response.aiter_lines():
        if line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
        elif not line and data:
            payload = "\n".join(data)
            data.clear()
            if payload == "[DONE]":
                return
            event = json.loads(payload)
            if isinstance(event, dict):
                yield event
    if data and "\n".join(data) != "[DONE]":
        event = json.loads("\n".join(data))
        if isinstance(event, dict):
            yield event


class KimiLLM(_ChatCompletionsLLM):
    """Streams from the Kimi Code platform (OpenAI-compatible) and runs the builtin `$web_search` tool when it is offered."""

    label = "Kimi Code"
    WEB_SEARCH = "$web_search"
    SEARCH_TOOL = WEB_SEARCH
    # Kimi Code documents no /models endpoint for keys, so a 404 there is no reason to call the key bad.
    probe_ok_statuses = (404, 405, 429)

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        super().__init__(settings, client)
        self._catalog = EffortCatalog(settings, client)
        self._search_rejected = False  # set when the endpoint refused the $web_search tool once

    def _api_key(self) -> str:
        return self._settings.kimi_api_key

    def _url(self) -> str:
        return f"{self._settings.kimi_base_url.rstrip('/')}/chat/completions"

    def _missing_key_message(self) -> str:
        return "KIMI_API_KEY is not configured on the server"

    def _probe_url(self) -> str:
        return f"{self._settings.kimi_base_url.rstrip('/')}/models"

    def _body(
        self,
        messages: list[dict[str, Any]],
        model: str,
        effort: str | None = None,
        web_search: bool | None = None,
        draw: str = "",
    ) -> dict[str, Any]:
        """`web_search` here means "offer the builtin search tool in this request" (see `_search_arg`)."""
        body: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": True,
            "max_tokens": self._settings.kimi_max_tokens,
            "stream_options": {"include_usage": True},
        }
        if effort == "none":
            body["thinking"] = {"type": "disabled"}
        elif effort:
            body["reasoning_effort"] = effort
        tools: list[dict[str, Any]] = []
        if web_search:
            tools.append({"type": "builtin_function", "function": {"name": self.WEB_SEARCH}})
        if draw == "auto":  # used up: withheld, like the search tool (thinking mode takes no tool_choice but auto)
            tools.append(chat_completions_tool())
        if tools:
            body["tools"] = tools
        return body

    def _search_arg(self, web_search: bool | None, searches: int, first_round: bool) -> bool | None:
        """Each answered search counts against WEB_SEARCH_MAX_USES; when they are used up the tool is withheld."""
        return (
            _web_search_enabled(self._settings, web_search)
            and not self._search_rejected
            and searches < self._settings.web_search_max_uses
        )

    def _assistant_extras(self, finished: _Round) -> dict[str, Any]:
        # Kimi wants its reasoning sent back with tool calls
        return {"reasoning_content": finished.reasoning} if finished.reasoning else {}

    def _tool_message(self, call: dict[str, Any], content: str) -> dict[str, Any]:
        return {"role": "tool", "tool_call_id": call["id"], "name": call["name"], "content": content}

    def _drop_search(self, body: dict[str, Any], exc: LLMError, finished: _Round) -> bool:
        """The endpoint may not offer the builtin search tool (Kimi Code does not document it): drop it, remember
        that, and ask again. Nothing was yielded yet: HTTP errors come first."""
        offered = any(t.get("type") == "builtin_function" for t in body.get("tools", []))
        if offered and exc.code == "upstream_error" and not finished.content:
            log.warning("Kimi rejected the $web_search tool (%s); continuing without web search", exc.message)
            self._search_rejected = True
            return True
        return False

    async def usage(self) -> UsageLeft | None:
        """Quota left of the Kimi Code membership, from GET <base>/usages (best effort: not in the public docs)."""
        url = f"{self._settings.kimi_base_url.rstrip('/')}/usages"
        try:
            response = await self._client.get(
                url,
                headers={"Authorization": f"Bearer {self._api_key()}", "User-Agent": USER_AGENT},
                timeout=PROBE_TIMEOUT,
            )
            response.raise_for_status()
            body = response.json()
            usage = body.get("usage") if isinstance(body, dict) else None
            limit, remaining = float(usage["limit"]), float(usage["remaining"])
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            log.debug("Kimi Code usage unavailable: %s", exc)
            return None
        share = _share(remaining, limit)
        return UsageLeft(share, f"{round(share * 100)}%" if share is not None else "")

    async def efforts(self, model: str) -> ModelEfforts:
        """Efforts of one Kimi model. Moonshot has no endpoint for this, so, in order:

        1. KIMI_MODEL_EFFORTS, the operator's per-model list (empty = not adjustable),
        2. OpenRouter's public catalog entry `moonshotai/<model>` (if KIMI_EFFORTS_FROM_CATALOG),
        3. KIMI_EFFORTS, the list for every model that has neither.
        """
        settings = self._settings
        own = settings.kimi_model_efforts.get(model)
        if own is not None:
            supported = normalize_efforts(own.split(","))
            return ModelEfforts(supported, pick_default(supported, settings.kimi_default_effort))
        if settings.kimi_efforts_from_catalog:
            known = await self._catalog.lookup(f"moonshotai/{model}")
            if known is not None:
                supported, catalog_default = known
                return ModelEfforts(supported, pick_default(supported, settings.kimi_default_effort, catalog_default))
        supported = normalize_efforts(settings.kimi_efforts.split(","))
        return ModelEfforts(supported, pick_default(supported, settings.kimi_default_effort))


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
        figures: bool | None = None,
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
        tools: list[dict[str, Any]] = []
        if _web_search_enabled(self._settings, web_search):
            tools.append({"type": WEB_SEARCH_TOOL, "name": "web_search", "max_uses": self._settings.web_search_max_uses})
        if _figures_enabled(self._settings, figures):
            tools.append(anthropic_tool())
        if tools:
            request["tools"] = tools
        return request

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        *,
        effort: str | None = None,
        web_search: bool | None = None,
        figures: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Figure | Usage]:
        """Yield text deltas (str), Thinking pieces, Figures and a final Usage item. Raises LLMError like OpenRouterLLM.

        Draw calls end a round with stop_reason "tool_use": the figures are yielded, the results sent back and the
        model asked again; once FIGURES_MAX_PER_ANSWER calls are used up the tool stays defined but tool_choice is "none".
        """
        if not self._settings.anthropic_api_key:
            raise LLMError("upstream_auth", "ANTHROPIC_API_KEY is not configured on the server")
        system, conversation = _anthropic_conversation(messages)
        if not conversation:
            raise LLMError("upstream_error", "no user message to answer")
        request = self._request(system, conversation, model, effort, web_search, figures)
        can_draw = _figures_enabled(self._settings, figures)
        drawn = pauses = 0
        prompt_tokens = completion_tokens = 0
        sources = SourceCollector()
        try:
            for _ in range(MAX_TOOL_ROUNDS):
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
                sources.add_anthropic_content(final.content)
                usage = final.usage
                prompt_tokens += (
                    usage.input_tokens
                    + (usage.cache_read_input_tokens or 0)
                    + (usage.cache_creation_input_tokens or 0)
                )
                completion_tokens += usage.output_tokens
                if final.stop_reason == "refusal":
                    raise LLMError("upstream_error", "the model declined to answer this request")
                if final.stop_reason == "pause_turn" and pauses < MAX_PAUSE_TURNS - 1:
                    # Resume the paused server-side tool loop: send the assistant turn back as it is.
                    pauses += 1
                    request["messages"] = [*request["messages"], {"role": "assistant", "content": final.content}]
                    continue
                calls = [b for b in final.content if getattr(b, "type", "") == "tool_use"]
                if final.stop_reason != "tool_use" or not calls or not can_draw:
                    break
                results: list[dict[str, Any]] = []
                for call in calls:
                    if call.name != DRAW_TOOL:
                        results.append({"type": "tool_result", "tool_use_id": call.id, "content": "unknown tool", "is_error": True})
                        continue
                    drawn += 1
                    figure, note = answer_draw(call.input)
                    if figure is not None:
                        yield figure
                    result: dict[str, Any] = {"type": "tool_result", "tool_use_id": call.id, "content": note}
                    if figure is None:
                        result["is_error"] = True
                    results.append(result)
                request["messages"] = [
                    *request["messages"],
                    {"role": "assistant", "content": final.content},
                    {"role": "user", "content": results},
                ]
                if drawn >= self._settings.figures_max_per_answer:
                    request["tool_choice"] = {"type": "none"}  # tool_use blocks in history need the tool still defined
        except anthropic.APIStatusError as exc:
            log.warning("Anthropic returned %s: %s", exc.status_code, exc.message)
            message = exc.message or "upstream request failed"
            retry_after = parse_retry_after(exc.response.headers.get("retry-after"))
            raise LLMError(error_code_for_status(exc.status_code, message), message, retry_after) from exc
        except anthropic.APIConnectionError as exc:
            log.warning("Anthropic request failed: %s", exc)
            raise LLMError("upstream_error", f"could not reach the model provider: {exc}") from exc
        if sources:
            yield sources.result()
        yield Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)


Provider = AnthropicLLM | OpenRouterLLM | KimiLLM | OpenAILLM


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
        figures: bool | None = None,
    ) -> AsyncIterator[str | Thinking | Figure | Usage]:
        """Pick the final effort for the model (requested, else its default, clamped to what it supports)."""
        name, provider = self._provider(model)
        if name not in await self.working_providers():
            health = self._health[name]
            raise LLMError(health.reason or "upstream_error", health.message or f"provider {name} is unavailable")
        info = await self.efforts(model)
        chosen = resolve_effort(effort, info.efforts) or info.default or None
        try:
            async for item in provider.stream(messages, model, effort=chosen, web_search=web_search, figures=figures):
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
        elif name == "kimi":
            providers[name] = KimiLLM(settings, client)
        elif name == "openai":
            providers[name] = OpenAILLM(settings, client)
    return MultiLLM(settings, providers)
