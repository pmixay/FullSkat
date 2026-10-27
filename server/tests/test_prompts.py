from datetime import date

from fullskat_server.prompts import build_system_prompt


def test_prompt_allows_math_and_code_markup():
    prompt = build_system_prompt("ru-RU", date(2026, 10, 27))
    # The watch renders LaTeX, fenced code and light Markdown now.
    assert "$$" in prompt and "\\frac" in prompt and "```python" in prompt
    assert "PLAIN TEXT ONLY" not in prompt
    # The format() placeholders are all filled and the escaped example braces came out single.
    assert "{date}" not in prompt and "{locale}" not in prompt
    assert "{{" not in prompt and "\\frac{-b" in prompt
    assert "Current date: 2026-10-27. User locale: ru-RU." in prompt


def test_prompt_keeps_answers_short_by_default():
    prompt = build_system_prompt("en-US")
    assert "at most about 60 words" in prompt
