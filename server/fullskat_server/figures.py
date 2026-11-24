"""The `draw` tool: figures the model draws and the watch renders natively.

The model calls `draw` with a small vector scene (function graphs, data charts, geometry, simple diagrams).
The server checks and compacts the call and streams it to the watch inside the answer as a fenced block:

    ```figure
    {"title":"y = sin x","x_range":[-6.283,6.283],"items":[{"type":"function","expr":"sin(x)"}]}
    ```

so it is stored with the message, shows up in history and needs no new SSE event. The watch evaluates the
expressions itself, draws the scene on a Canvas and animates it item by item. See docs/PROTOCOL.md.
"""

from __future__ import annotations

import json
import math
import re
from typing import Any

DRAW_TOOL = "draw"
FENCE_LANGUAGE = "figure"

ITEM_TYPES = (
    "function", "parametric", "polar", "line", "scatter", "bars", "pie", "point", "segment", "arrow",
    "vline", "hline", "polygon", "circle", "arc", "angle", "rect", "text", "path",
)
COLORS = ("amber", "blue", "magenta", "violet", "aqua", "red", "gray", "white")

MAX_ITEMS = 32
MAX_POINTS = 500
MAX_CATEGORIES = 24
MAX_LABEL = 80
MAX_TITLE = 120
MAX_EXPR = 300
MAX_PATH = 4000

# Names the watch's expression evaluator knows (richtext/figure/Expr.kt); keep both lists in sync.
FUNCTIONS = frozenset({
    "sin", "cos", "tan", "cot", "sec", "csc", "asin", "acos", "atan", "arcsin", "arccos", "arctan", "acot",
    "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "exp", "ln", "log", "log10", "log2", "lg", "sqrt", "cbrt",
    "abs", "sign", "sgn", "floor", "ceil", "round", "min", "max", "pow", "mod", "hypot", "atan2", "if",
})
CONSTANTS = frozenset({"pi", "π", "e", "tau", "τ", "inf"})
VARIABLES = {"function": frozenset({"x"}), "parametric": frozenset({"t"}), "polar": frozenset({"t", "theta", "θ"})}

_TOKEN = re.compile(r"\s*(?:(\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?)|([A-Za-z_πθτ][A-Za-z0-9_]*)|(\*\*|<=|>=|==|!=|[-+*/^%(),|<>]))")
_PATH_CHARS = re.compile(r"^[MmLlHhVvCcSsQqTtAaZz0-9eE+\-.,\s]*$")
_HEX = re.compile(r"^#[0-9a-fA-F]{6}$")

TOOL_DESCRIPTION = (
    "Draw a figure on the user's round watch screen (about 1.3 inches): function graphs, parametric and polar "
    "curves, data charts (line, scatter, bars, pie), geometry and simple diagrams (points, segments, arrows, "
    "polygons, circles, arcs, angle marks, boxes, text). The watch renders and animates it natively, in the order "
    "of `items`. Coordinates are math-style (y grows upwards) in one plane shared by all items. Expressions use "
    "x (function) or t (parametric, polar; radians), + - * / ^, implicit multiplication (2x), |x|, pi, e and "
    "sin cos tan asin acos atan sinh cosh tanh exp ln log (natural) log10 log2 sqrt cbrt abs sign floor ceil "
    "round min max pow mod atan2 and if(cond, a, b) with < <= > >= == !=. Labels may contain LaTeX in $...$. "
    "Keep it simple: at most 3 curves or 8 bars, short labels. Give x_range for functions; y is fitted when "
    "omitted. Set axes=false for geometry and diagrams (equal scale is then automatic)."
)

_POINT = {"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2}
_RANGE = {"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2}

TOOL_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "title": {"type": "string", "description": "Short caption shown above the figure; LaTeX in $...$ allowed."},
        "x_range": {**_RANGE, "description": "[min, max] of the visible x axis."},
        "y_range": {**_RANGE, "description": "[min, max] of the visible y axis; fitted to the items when omitted."},
        "x_label": {"type": "string", "description": "Name of the x axis, e.g. \"t, s\"."},
        "y_label": {"type": "string", "description": "Name of the y axis."},
        "axes": {"type": "boolean", "description": "Draw axes with ticks (default true). False for geometry and diagrams."},
        "grid": {"type": "boolean", "description": "Draw a faint grid (default: same as axes)."},
        "equal": {"type": "boolean", "description": "Same scale on both axes so circles stay round (default: true when axes is false)."},
        "items": {
            "type": "array",
            "minItems": 1,
            "maxItems": MAX_ITEMS,
            "description": "What to draw, in drawing order.",
            "items": {
                "type": "object",
                "properties": {
                    "type": {
                        "type": "string",
                        "enum": list(ITEM_TYPES),
                        "description": (
                            "function: expr in x (optional domain, area). parametric: x and y in t, t range. "
                            "polar: expr r(t), t range (default [0, 2pi]). line/scatter: points (data series). "
                            "bars: values (+labels), one item per series. pie: values + labels (alone in the figure). "
                            "point: at. segment/arrow: from, to. vline/hline: value. polygon: points. "
                            "circle: center, r. arc: center, r, start, end (degrees, counterclockwise). "
                            "angle: at = vertex, from and to = points on its two rays (90 degrees gets a square mark). "
                            "rect: from, to = opposite corners (a box with label inside, for diagrams). "
                            "text: at, text. path: d = SVG path data in figure coordinates."
                        ),
                    },
                    "expr": {"type": "string", "description": "function: y as an expression in x; polar: r as an expression in t."},
                    "x": {"type": "string", "description": "parametric: x as an expression in t."},
                    "y": {"type": "string", "description": "parametric: y as an expression in t."},
                    "t": {**_RANGE, "description": "parametric/polar: [t_min, t_max] in radians."},
                    "domain": {**_RANGE, "description": "function: draw only for x in [a, b]."},
                    "area": {**_RANGE, "description": "function: shade the area between the curve and the x axis for x in [a, b]."},
                    "points": {"type": "array", "items": _POINT, "maxItems": MAX_POINTS, "description": "[[x, y], ...]"},
                    "values": {"type": "array", "items": {"type": "number"}, "maxItems": MAX_CATEGORIES},
                    "labels": {"type": "array", "items": {"type": "string"}, "maxItems": MAX_CATEGORIES, "description": "bars/pie: category names."},
                    "at": _POINT,
                    "from": _POINT,
                    "to": _POINT,
                    "center": _POINT,
                    "r": {"type": "number", "description": "circle/arc radius."},
                    "start": {"type": "number", "description": "arc: start angle in degrees."},
                    "end": {"type": "number", "description": "arc: end angle in degrees."},
                    "value": {"type": "number", "description": "vline: x; hline: y."},
                    "text": {"type": "string"},
                    "d": {"type": "string", "description": "path: SVG path data (M L H V C S Q T A Z)."},
                    "label": {"type": "string", "description": "Legend entry of a curve or series, or a label next to a shape."},
                    "color": {"type": "string", "description": "amber, blue, magenta, violet, aqua, red, gray, white or #RRGGBB; series get distinct colors by default."},
                    "dash": {"type": "boolean", "description": "Dashed line (asymptotes, helper lines)."},
                    "fill": {"type": "boolean", "description": "Fill a closed shape with a translucent color."},
                },
                "required": ["type"],
            },
        },
    },
    "required": ["items"],
}


def anthropic_tool() -> dict[str, Any]:
    return {"name": DRAW_TOOL, "description": TOOL_DESCRIPTION, "input_schema": TOOL_SCHEMA}


def chat_completions_tool() -> dict[str, Any]:
    return {"type": "function", "function": {"name": DRAW_TOOL, "description": TOOL_DESCRIPTION, "parameters": TOOL_SCHEMA}}


def responses_tool() -> dict[str, Any]:
    return {"type": "function", "name": DRAW_TOOL, "description": TOOL_DESCRIPTION, "parameters": TOOL_SCHEMA, "strict": False}


SHOWN = "The figure is on the watch screen now. Do not describe it in detail; add at most one short sentence."


class FigureError(ValueError):
    """A draw call that cannot be shown; the message goes back to the model as the tool error."""


def parse_arguments(raw: Any) -> dict[str, Any]:
    """Tool arguments as a dict: Claude hands over a dict, chat-completions/Responses APIs a JSON string."""
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str) or not raw.strip():
        raise FigureError("draw needs a JSON object with an items array")
    try:
        value = json.loads(raw)
    except ValueError as exc:
        raise FigureError(f"the arguments are not valid JSON ({exc.msg})") from exc
    if not isinstance(value, dict):
        raise FigureError("draw needs a JSON object with an items array")
    return value


def normalize_figure(raw: Any) -> tuple[dict[str, Any], str]:
    """Check and compact a draw call. Returns (figure, note for the model). Raises FigureError if nothing is drawable.

    Unknown keys are dropped, numbers rounded to 6 significant digits; broken items are skipped and named in the note.
    """
    args = parse_arguments(raw)
    items_in = args.get("items")
    if not isinstance(items_in, list) or not items_in:
        raise FigureError("items must be a non-empty array")
    figure: dict[str, Any] = {}
    title = _text(args.get("title"), MAX_TITLE)
    if title:
        figure["title"] = title
    for key in ("x_range", "y_range"):
        value = _range(args.get(key))
        if value is not None:
            figure[key] = value
    for key in ("x_label", "y_label"):
        value = _text(args.get(key), MAX_LABEL)
        if value:
            figure[key] = value
    for key in ("axes", "grid", "equal"):
        if isinstance(args.get(key), bool):
            figure[key] = args[key]

    items: list[dict[str, Any]] = []
    problems: list[str] = []
    for index, raw_item in enumerate(items_in[:MAX_ITEMS]):
        try:
            items.append(_item(raw_item))
        except FigureError as exc:
            kind = raw_item.get("type") if isinstance(raw_item, dict) else None
            problems.append(f"items[{index}]" + (f" ({kind})" if isinstance(kind, str) else "") + f": {exc}")
    if len(items_in) > MAX_ITEMS:
        problems.append(f"only the first {MAX_ITEMS} items are drawn")
    if not items:
        raise FigureError("nothing to draw: " + "; ".join(problems))
    figure["items"] = items
    note = SHOWN if not problems else f"{SHOWN} Skipped: " + "; ".join(problems)
    return figure, note


def fenced_block(figure: dict[str, Any]) -> str:
    """The figure as the watch expects it in the answer text: one line of JSON in a ```figure fence.

    Backticks inside strings are escaped so no line of the JSON can end the fence.
    """
    payload = json.dumps(figure, ensure_ascii=False, separators=(",", ":")).replace("`", "\\u0060")
    return f"```{FENCE_LANGUAGE}\n{payload}\n```"


# ---- items -------------------------------------------------------------------


def _item(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise FigureError("an item must be an object")
    kind = raw.get("type")
    if kind not in ITEM_TYPES:
        raise FigureError(f"unknown type {kind!r}; use one of {', '.join(ITEM_TYPES)}")
    item: dict[str, Any] = {"type": kind}

    if kind == "function":
        item["expr"] = _expr(raw.get("expr"), VARIABLES["function"], "expr")
        _copy_range(raw, item, "domain")
        _copy_range(raw, item, "area")
    elif kind == "parametric":
        item["x"] = _expr(raw.get("x"), VARIABLES["parametric"], "x")
        item["y"] = _expr(raw.get("y"), VARIABLES["parametric"], "y")
        _copy_range(raw, item, "t")
    elif kind == "polar":
        item["expr"] = _expr(raw.get("expr") or raw.get("r"), VARIABLES["polar"], "expr")
        _copy_range(raw, item, "t")
    elif kind in ("line", "scatter", "polygon"):
        least = {"line": 2, "scatter": 1, "polygon": 3}[kind]
        item["points"] = _points(raw.get("points"), least)
    elif kind in ("bars", "pie"):
        values = raw.get("values")
        if not isinstance(values, list) or not values:
            raise FigureError("values must be a non-empty array of numbers")
        item["values"] = [_number(v, "values") for v in values[:MAX_CATEGORIES]]
        if kind == "pie" and (any(v < 0 for v in item["values"]) or sum(item["values"]) <= 0):
            raise FigureError("pie values must be non-negative with a positive sum")
        labels = raw.get("labels")
        if isinstance(labels, list) and labels:
            item["labels"] = [_text(label, MAX_LABEL) for label in labels[: len(item["values"])]]
    elif kind in ("point", "text"):
        item["at"] = _point(raw.get("at"), "at")
        if kind == "text":
            text = _text(raw.get("text") or raw.get("label"), MAX_TITLE)
            if not text:
                raise FigureError("text needs text")
            item["text"] = text
    elif kind in ("segment", "arrow", "rect"):
        item["from"] = _point(raw.get("from"), "from")
        item["to"] = _point(raw.get("to"), "to")
    elif kind in ("vline", "hline"):
        item["value"] = _number(raw.get("value"), "value")
    elif kind in ("circle", "arc"):
        item["center"] = _point(raw.get("center"), "center")
        item["r"] = _number(raw.get("r"), "r")
        if item["r"] <= 0:
            raise FigureError("r must be positive")
        if kind == "arc":
            item["start"] = _number(raw.get("start", 0), "start")
            item["end"] = _number(raw.get("end"), "end")
    elif kind == "angle":
        item["at"] = _point(raw.get("at"), "at")
        item["from"] = _point(raw.get("from"), "from")
        item["to"] = _point(raw.get("to"), "to")
    elif kind == "path":
        d = raw.get("d")
        if not isinstance(d, str) or not d.strip() or len(d) > MAX_PATH or not _PATH_CHARS.match(d):
            raise FigureError(f"d must be SVG path data (commands MLHVCSQTAZ and numbers, at most {MAX_PATH} characters)")
        item["d"] = " ".join(d.split())

    label = _text(raw.get("label"), MAX_LABEL)
    if label and kind != "text":
        item["label"] = label
    color = raw.get("color")
    if isinstance(color, str):
        color = color.strip().lower()
        if color in COLORS or _HEX.match(color):
            item["color"] = color
    for key in ("dash", "fill"):
        if raw.get(key) is True:
            item[key] = True
    return item


def _number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise FigureError(f"{name} must be a finite number")
    return _round(float(value))


def _round(value: float) -> float | int:
    rounded = float(f"{value:.6g}")
    return int(rounded) if rounded.is_integer() and abs(rounded) < 1e15 else rounded


def _point(value: Any, name: str) -> list[float]:
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        raise FigureError(f"{name} must be [x, y]")
    return [_number(value[0], name), _number(value[1], name)]


def _points(value: Any, least: int) -> list[list[float]]:
    if not isinstance(value, list) or len(value) < least:
        raise FigureError(f"points must be an array of at least {least} [x, y] pairs")
    if len(value) > MAX_POINTS:
        raise FigureError(f"at most {MAX_POINTS} points")
    return [_point(p, "points") for p in value]


def _range(value: Any) -> list[float] | None:
    try:
        low, high = _point(value, "range")
    except FigureError:
        return None
    return [low, high] if low < high else None


def _copy_range(raw: dict[str, Any], item: dict[str, Any], key: str) -> None:
    value = _range(raw.get(key))
    if value is not None:
        item[key] = value


def _text(value: Any, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    return " ".join(value.split())[:limit]


def _expr(value: Any, variables: frozenset[str], name: str) -> str:
    """Light syntax check of an expression (the watch parses it for real): known names, balanced brackets."""
    if not isinstance(value, str) or not value.strip():
        raise FigureError(f"{name} must be an expression")
    text = value.strip()
    if len(text) > MAX_EXPR:
        raise FigureError(f"{name} is longer than {MAX_EXPR} characters")
    position = depth = bars = 0
    while position < len(text):
        match = _TOKEN.match(text, position)
        if not match or match.end() == position:
            rest = text[position:].strip()
            if not rest:
                break
            raise FigureError(f"{name}: unexpected {rest[0]!r} in {text!r}")
        number, ident, op = match.groups()
        if ident is not None:
            word = ident.lower() if ident.isascii() else ident
            if word not in FUNCTIONS and word not in CONSTANTS and word not in variables:
                allowed = ", ".join(sorted(variables))
                raise FigureError(f"{name}: unknown name {ident!r} (the variable is {allowed})")
        elif op == "(":
            depth += 1
        elif op == ")":
            depth -= 1
            if depth < 0:
                raise FigureError(f"{name}: unbalanced ')' in {text!r}")
        elif op == "|":
            bars += 1
        position = match.end()
    if depth != 0:
        raise FigureError(f"{name}: unbalanced parentheses in {text!r}")
    if bars % 2:
        raise FigureError(f"{name}: unbalanced |...| in {text!r}")
    return text
