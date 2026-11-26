"""The real FullSkat server (protocol v2) with a scripted model instead of a provider, for interop tests.

    python3 tools/interop/fake_server.py 8765 /tmp/fullskat-interop

Every answer streams a short thinking summary, a sentence, a figure and a closing sentence, so a client can
check text, thinking and ```figure blocks end to end without an API key. Used by core's ServerInteropTest.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, AsyncIterator

import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "server"))

from fullskat_server.app import create_app  # noqa: E402
from fullskat_server.config import Settings  # noqa: E402
from fullskat_server.efforts import ModelEfforts  # noqa: E402
from fullskat_server.figures import normalize_figure  # noqa: E402
from fullskat_server.llm import Figure, Thinking, Usage  # noqa: E402

MODELS = ["claude-haiku-5-5", "claude-sonnet-5-5"]
SINE = {"title": "y = sin x", "x_range": [-6.28319, 6.28319], "items": [{"type": "function", "expr": "sin(x)"}]}


class ScriptedLLM:
    def __init__(self) -> None:
        self.requests: list[list[dict[str, Any]]] = []

    async def models(self) -> tuple[str, list[str]]:
        return MODELS[0], MODELS

    async def providers_info(self) -> dict[str, dict[str, object]]:
        return {"anthropic": {"ok": True, "reason": "", "models": MODELS, "usage": {"remaining": 0.5, "label": ""}}}

    async def model_efforts(self, models: list[str]) -> dict[str, ModelEfforts]:
        return {m: ModelEfforts(("low", "medium", "high"), "medium") for m in models}

    async def stream(self, messages: list[dict[str, Any]], model: str, **options: Any) -> AsyncIterator[Any]:
        self.requests.append(messages)
        images = sum(len(m.get("images") or []) for m in messages)
        yield Thinking("Думаю. ")
        yield f"Вижу {images} изображ. "
        yield Figure(normalize_figure(SINE)[0])
        yield "Это синус."
        yield Usage(prompt_tokens=5, completion_tokens=7)


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    data = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/tmp/fullskat-interop")
    data.mkdir(parents=True, exist_ok=True)
    settings = Settings(_env_file=None, data_dir=data, anthropic_api_key="interop", server_name="Interop")
    app = create_app(settings=settings)
    app.state.llm = ScriptedLLM()
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")


if __name__ == "__main__":
    main()
