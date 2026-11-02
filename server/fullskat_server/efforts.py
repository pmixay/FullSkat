"""Reasoning-effort levels and per-model support.

Providers disagree about which levels a model accepts (Claude: low..max, Kimi K3: max/high/low/none,
Gemini: minimal..high, Kimi K2.x: none at all), so the server asks each provider what a model supports
and tells the watch, which then offers only those levels.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

# Lowest to highest. "none" turns reasoning off; it is never chosen as a stand-in for a real level.
EFFORT_LEVELS = ("none", "minimal", "low", "medium", "high", "xhigh", "max")


@dataclass(frozen=True)
class ModelEfforts:
    """Levels a model accepts (empty: effort is not adjustable) and the one used when the watch sends none."""

    efforts: tuple[str, ...] = ()
    default: str = ""

    def to_json(self) -> dict[str, object]:
        return {"efforts": list(self.efforts), "default": self.default}


def normalize_efforts(levels: Iterable[object]) -> tuple[str, ...]:
    """Known levels only, unique, lowest first."""
    wanted = {str(level).strip().lower() for level in levels}
    return tuple(level for level in EFFORT_LEVELS if level in wanted)


def resolve_effort(requested: str | None, supported: tuple[str, ...]) -> str | None:
    """The supported level closest to `requested` (ties go lower), or None if effort is not adjustable."""
    if not requested or not supported:
        return None
    if requested in supported:
        return requested
    if requested not in EFFORT_LEVELS:
        return None
    candidates = [level for level in supported if level != "none"] or list(supported)
    target = EFFORT_LEVELS.index(requested)
    return min(candidates, key=lambda level: (abs(EFFORT_LEVELS.index(level) - target), EFFORT_LEVELS.index(level)))


def pick_default(supported: tuple[str, ...], *preferred: str | None) -> str:
    """First of the preferred levels that the model can honour; "" when effort is not adjustable."""
    for level in preferred:
        resolved = resolve_effort(level, supported)
        if resolved:
            return resolved
    return ""
