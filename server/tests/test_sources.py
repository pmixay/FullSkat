from types import SimpleNamespace

from fullskat_server.sources import MAX_SOURCES, Source, SourceCollector, Sources, clean_title, clean_url


def test_clean_url_keeps_http_links_without_tracking() -> None:
    assert clean_url("https://openai.com/index/x/?utm_source=openai") == "https://openai.com/index/x/"
    assert clean_url(" HTTPS://Example.com/a?b=1&utm_medium=x&c=%2F#frag ") == "https://Example.com/a?b=1&c=%2F"
    assert clean_url("http://example.com") == "http://example.com"
    for bad in ("javascript:alert(1)", "ftp://example.com/f", "mailto:a@b.c", "https://", "", None, 42,
                "https://exa mple.com", "https://x.com/" + "a" * 3000, "//example.com/a"):
        assert clean_url(bad) == "", bad


def test_clean_title_collapses_whitespace_and_caps_length() -> None:
    assert clean_title("  Погода\n в  Москве ") == "Погода в Москве"
    assert clean_title(None) == ""
    long = clean_title("x" * 500)
    assert len(long) == 200 and long.endswith("…")


def test_cited_pages_come_first_deduplicated_in_order() -> None:
    sources = SourceCollector()
    assert not sources and sources.result() == Sources()
    sources.consult("https://b.example/page", "B from search")
    sources.cite("https://a.example/x", "A")
    sources.cite("https://b.example/page/?utm_source=openai")  # same page, no title yet
    sources.cite("https://www.a.example/x#part", "A again")  # www, fragment: still the same page
    sources.cite("javascript:alert(1)", "nope")
    assert sources.result().to_json() == [
        {"url": "https://a.example/x", "title": "A"},
        {"url": "https://b.example/page/", "title": "B from search"},  # title filled from the search result
    ]


def test_consulted_pages_are_the_fallback_and_everything_is_capped() -> None:
    sources = SourceCollector()
    for i in range(12):
        sources.consult(f"https://site{i}.example/", f"Site {i}")
    result = sources.result()
    assert len(result.items) == MAX_SOURCES
    assert result.items[0] == Source("https://site0.example/", "Site 0")


def test_merge_keeps_order_drops_duplicates_and_caps() -> None:
    first = Sources((Source("https://a.example/", "A"), Source("https://b.example/", "B")))
    second = Sources((Source("https://a.example", "A dup"),) + tuple(Source(f"https://c{i}.example/") for i in range(10)))
    merged = first.merge(second)
    assert [s.url for s in merged.items[:3]] == ["https://a.example/", "https://b.example/", "https://c0.example/"]
    assert len(merged.items) == MAX_SOURCES
    assert Sources().merge(first) == first and not Sources()


def test_chat_completions_annotations_nested_and_flat() -> None:
    sources = SourceCollector()
    sources.add_chat_choice({"delta": {"content": "x"}})
    sources.add_chat_choice({"delta": {"annotations": [
        {"type": "url_citation", "url_citation": {"url": "https://or.example/1", "title": "One", "content": "..."}},
        {"type": "file_citation", "file_id": "f"},
        "garbage",
    ]}})
    sources.add_chat_choice({"message": {"annotations": [{"type": "url_citation", "url": "https://or.example/2", "title": "Two"}]}})
    sources.add_chat_choice({"delta": {"annotations": None}})
    assert sources.result().to_json() == [
        {"url": "https://or.example/1", "title": "One"},
        {"url": "https://or.example/2", "title": "Two"},
    ]


def test_responses_events_citations_and_consulted_pages() -> None:
    cited = SourceCollector()
    cited.add_responses_event({"type": "response.output_text.delta", "delta": "hi"})
    cited.add_responses_event({"type": "response.output_item.done", "item": {
        "type": "web_search_call", "status": "completed",
        "action": {"type": "search", "query": "q", "sources": [
            {"type": "url", "url": "https://consulted.example/a"},
            {"type": "api", "name": "oai-weather"},  # real-time feeds have no URL
        ]},
    }})
    cited.add_responses_event({"type": "response.output_text.annotation.added", "annotation": {
        "type": "url_citation", "url": "https://cited.example/?utm_source=openai", "title": "Cited", "start_index": 0, "end_index": 4,
    }})
    cited.add_responses_event({"type": "response.output_text.annotation.added", "annotation": None})
    # The completed response repeats the message: nothing new is added.
    cited.add_responses_event({"type": "response.completed", "response": {"output": [
        {"type": "message", "content": [{"type": "output_text", "text": "hi", "annotations": [
            {"type": "url_citation", "url": "https://cited.example/", "title": "Cited"},
        ]}]},
    ]}})
    assert cited.result().to_json() == [{"url": "https://cited.example/", "title": "Cited"}]

    consulted = SourceCollector()
    consulted.add_responses_event({"type": "response.output_item.done", "item": {
        "type": "web_search_call", "action": {"type": "open_page", "url": "https://opened.example/page"}}})
    consulted.add_responses_event({"type": "response.output_item.done", "item": {
        "type": "web_search_call", "action": {"type": "find_in_page", "url": "https://opened.example/page", "pattern": "x"}}})
    consulted.add_responses_event({"type": "response.completed", "response": {"output": [
        {"type": "web_search_call", "action": {"type": "search", "sources": [{"type": "url", "url": "https://s.example/"}]}},
    ]}})
    assert consulted.result().to_json() == [
        {"url": "https://opened.example/page", "title": ""},
        {"url": "https://s.example/", "title": ""},
    ]


def test_anthropic_content_citations_and_search_results() -> None:
    def ns(**fields: object) -> SimpleNamespace:
        return SimpleNamespace(**fields)

    content = [
        ns(type="text", text="Let me check.", citations=None),
        ns(type="server_tool_use", id="srv", name="web_search", input={"query": "q"}),
        ns(type="web_search_tool_result", tool_use_id="srv", content=[
            ns(type="web_search_result", url="https://ru.wikipedia.org/wiki/X", title="X — Википедия", encrypted_content="..."),
            ns(type="web_search_result", url="https://news.example/item", title="News"),
        ]),
        ns(type="web_search_tool_result", tool_use_id="srv2", content=ns(type="web_search_tool_result_error", error_code="max_uses_exceeded")),
        {"type": "text", "text": "Answer", "citations": [
            {"type": "web_search_result_location", "url": "https://news.example/item", "title": "News", "cited_text": "..."},
            {"type": "char_location", "document_index": 0},
        ]},
        "unexpected",
    ]
    cited = SourceCollector()
    cited.add_anthropic_content(content)
    assert cited.result().to_json() == [{"url": "https://news.example/item", "title": "News"}]

    uncited = SourceCollector()
    uncited.add_anthropic_content(content[:4])
    assert [s.url for s in uncited.result().items] == ["https://ru.wikipedia.org/wiki/X", "https://news.example/item"]
    uncited.add_anthropic_content(None)
