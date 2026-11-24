"""Web sources behind an answer, read from each provider's own reporting (pure functions, no I/O).

What each provider gives back:

* OpenRouter (chat completions, `openrouter:web_search`): `annotations` of type `url_citation`
  (`{"type": "url_citation", "url_citation": {"url", "title", "content", ...}}`) on the streamed delta
  or on the final message.
* OpenAI Responses (`web_search` tool): `response.output_text.annotation.added` events and the
  `annotations` of `output_text` parts (`{"type": "url_citation", "url", "title", ...}`), plus the
  `web_search_call` items, whose `action.sources` (`{"type": "url", "url"}`, only with
  `include: ["web_search_call.action.sources"]`) and `open_page` URLs list what was consulted.
* Claude (`web_search` server tool): `citations` of type `web_search_result_location` on text blocks,
  and `web_search_tool_result` blocks with every result (`url`, `title`).
* Kimi's builtin `$web_search` reports nothing: the tool call carries only an opaque search id.

Pages the answer cites come first, in order of first citation. Pages that were only consulted (search
results, opened pages) are used only when nothing was cited, because they include many pages the
answer did not use. URLs are deduplicated (ignoring fragments, `utm_*` tracking parameters and a
trailing slash) and capped at `MAX_SOURCES`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable
from urllib.parse import urlsplit, urlunsplit

MAX_SOURCES = 8
MAX_TITLE = 200
MAX_URL = 2048


@dataclass(frozen=True)
class Source:
    url: str
    title: str = ""

    def to_json(self) -> dict[str, str]:
        return {"url": self.url, "title": self.title}


@dataclass(frozen=True)
class Sources:
    """Stream item: the web pages an answer drew on (empty when it did not search)."""

    items: tuple[Source, ...] = ()

    def to_json(self) -> list[dict[str, str]]:
        return [source.to_json() for source in self.items]

    def merge(self, other: Sources) -> Sources:
        """Both lists in order, deduplicated and capped (a tool loop may report sources once per round)."""
        seen = {_key(s.url) for s in self.items}
        merged = list(self.items)
        for source in other.items:
            key = _key(source.url)
            if key not in seen and len(merged) < MAX_SOURCES:
                seen.add(key)
                merged.append(source)
        return Sources(tuple(merged))

    def __bool__(self) -> bool:
        return bool(self.items)


def clean_url(url: Any) -> str:
    """An http(s) URL without its fragment and `utm_*` tracking parameters, or "" if it is not one."""
    if not isinstance(url, str):
        return ""
    url = url.strip()
    if not url or len(url) > MAX_URL or any(c.isspace() for c in url):
        return ""
    try:
        parts = urlsplit(url)
    except ValueError:
        return ""
    if parts.scheme.lower() not in ("http", "https") or not parts.hostname:
        return ""
    # Drop tracking parameters (OpenAI appends utm_source=openai) and keep the rest exactly as it was encoded.
    query = "&".join(p for p in parts.query.split("&") if p and not p.lower().startswith("utm_"))
    return urlunsplit((parts.scheme.lower(), parts.netloc, parts.path, query, ""))


def _key(url: str) -> str:
    """Identity of a page for deduplication: host case and a trailing slash do not matter."""
    parts = urlsplit(url)
    return urlunsplit(("", (parts.hostname or "").removeprefix("www."), parts.path.rstrip("/"), parts.query, ""))


def clean_title(title: Any) -> str:
    if not isinstance(title, str):
        return ""
    title = " ".join(title.split())
    return title if len(title) <= MAX_TITLE else title[: MAX_TITLE - 1].rstrip() + "…"


def _get(obj: Any, name: str) -> Any:
    """A field of a JSON dict or of an SDK object (the Anthropic SDK returns pydantic models)."""
    if isinstance(obj, dict):
        return obj.get(name)
    return getattr(obj, name, None)


def _list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


class SourceCollector:
    """Gathers cited and consulted pages while a response streams; `result()` turns them into `Sources`."""

    def __init__(self) -> None:
        self._cited: dict[str, Source] = {}
        self._consulted: dict[str, Source] = {}

    def __bool__(self) -> bool:
        return bool(self._cited or self._consulted)

    # -- recording --
    def _add(self, into: dict[str, Source], url: Any, title: Any) -> None:
        url = clean_url(url)
        if not url:
            return
        key, title = _key(url), clean_title(title)
        known = into.get(key)
        if known is None:
            into[key] = Source(url, title)
        elif title and not known.title:
            into[key] = Source(known.url, title)

    def cite(self, url: Any, title: Any = "") -> None:
        self._add(self._cited, url, title)

    def consult(self, url: Any, title: Any = "") -> None:
        self._add(self._consulted, url, title)

    def add_annotations(self, annotations: Any) -> None:
        """`url_citation` annotations, nested (`{"url_citation": {...}}`, chat completions) or flat (Responses)."""
        for annotation in _list(annotations):
            if _get(annotation, "type") != "url_citation":
                continue
            citation = _get(annotation, "url_citation") or annotation
            self.cite(_get(citation, "url"), _get(citation, "title"))

    # -- providers --
    def add_chat_choice(self, choice: Any) -> None:
        """One streamed chat-completions choice (OpenRouter): annotations on the delta or a final message."""
        for part in ("delta", "message"):
            self.add_annotations(_get(_get(choice, part), "annotations"))

    def add_responses_item(self, item: Any) -> None:
        """One Responses output item: a message's citations, or the pages a `web_search_call` consulted."""
        kind = _get(item, "type")
        if kind == "message":
            for part in _list(_get(item, "content")):
                self.add_annotations(_get(part, "annotations"))
        elif kind == "web_search_call":
            action = _get(item, "action")
            for source in _list(_get(action, "sources")):
                self.consult(_get(source, "url"), _get(source, "title"))  # `type: "api"` feeds have no URL
            if _get(action, "type") == "open_page":
                self.consult(_get(action, "url"))

    def add_responses_event(self, event: Any) -> None:
        """One Responses stream event; the completed response is read too, in case a gateway skipped the events."""
        kind = _get(event, "type")
        if kind == "response.output_text.annotation.added":
            self.add_annotations([_get(event, "annotation")])
        elif kind == "response.output_item.done":
            self.add_responses_item(_get(event, "item"))
        elif kind == "response.completed":
            for item in _list(_get(_get(event, "response"), "output")):
                self.add_responses_item(item)

    def add_anthropic_content(self, content: Iterable[Any] | None) -> None:
        """Content blocks of a final Claude message: text citations and web search results."""
        for block in content or ():
            kind = _get(block, "type")
            if kind == "text":
                for citation in _get(block, "citations") or ():
                    if _get(citation, "type") == "web_search_result_location":
                        self.cite(_get(citation, "url"), _get(citation, "title"))
            elif kind == "web_search_tool_result":
                for result in _list(_get(block, "content")):  # an error result is a single object, not a list
                    if _get(result, "type") == "web_search_result":
                        self.consult(_get(result, "url"), _get(result, "title"))

    # -- result --
    def result(self) -> Sources:
        """Cited pages (titles filled from the consulted ones), or the consulted pages if nothing was cited."""
        chosen = self._cited or self._consulted
        items = [
            Source(s.url, s.title or (self._consulted.get(key) or s).title)
            for key, s in list(chosen.items())[:MAX_SOURCES]
        ]
        return Sources(tuple(items))
