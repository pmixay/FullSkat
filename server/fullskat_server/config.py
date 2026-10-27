"""Server settings, loaded from environment variables (see server/.env.example)."""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_ALLOWED_MODELS = (
    "openai/gpt-5.6-luna,openai/gpt-5.6-luna-pro,openai/gpt-5.5,"
    "google/gemini-3.5-flash,anthropic/claude-sonnet-5"
)
DEFAULT_ANTHROPIC_ALLOWED_MODELS = "claude-haiku-5-5,claude-sonnet-5-5,claude-opus-5-5,claude-fable-5-1"


class Settings(BaseSettings):
    """All tunables of the server. Every field maps to an env var of the same (upper-case) name."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # Chat provider: "anthropic" (default, Claude API) or "openrouter"
    llm_provider: Literal["anthropic", "openrouter"] = "anthropic"
    # Let the model search the web (Anthropic web_search tool / OpenRouter web plugin)
    web_search: bool = True
    web_search_max_uses: int = Field(default=3, ge=1, le=20)
    # Stream the model's reasoning summary to the watch (SSE `thinking` events)
    show_thinking: bool = True

    # Anthropic (chat)
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-haiku-5-5"
    anthropic_effort: str = "medium"
    anthropic_allowed_models: str = DEFAULT_ANTHROPIC_ALLOWED_MODELS
    anthropic_base_url: str = ""
    # Upper bound for one answer including adaptive thinking tokens.
    anthropic_max_tokens: int = Field(default=4096, ge=1)

    # OpenRouter (chat, used when LLM_PROVIDER=openrouter)
    openrouter_api_key: str = ""
    openrouter_model: str = "openai/gpt-5.6-luna"
    openrouter_reasoning_effort: str = "high"
    openrouter_allowed_models: str = DEFAULT_ALLOWED_MODELS
    openrouter_base_url: str = "https://openrouter.ai/api/v1"

    # Identity and storage
    server_name: str = "FullSkat"
    data_dir: Path = Path("./data")

    # Rate limits
    pair_per_min: int = Field(default=5, ge=1)
    pair_lock_failures: int = Field(default=10, ge=1)
    pair_lock_minutes: int = Field(default=15, ge=1)
    chat_per_min: int = Field(default=20, ge=1)
    chat_per_day: int = Field(default=500, ge=1)

    # Chat shaping
    max_history_messages: int = Field(default=24, ge=1)
    max_history_chars: int = Field(default=12000, ge=1)
    max_completion_tokens: int = Field(default=700, ge=1)

    # Networking / misc
    trust_proxy_headers: bool = False
    log_level: str = "INFO"

    @field_validator("openrouter_reasoning_effort", "anthropic_effort", mode="before")
    @classmethod
    def _blank_effort_means_none(cls, value: object) -> str:
        return "" if value is None else str(value).strip()

    @property
    def chat_model(self) -> str:
        """Default chat model of the active provider."""
        return self.anthropic_model if self.llm_provider == "anthropic" else self.openrouter_model

    @property
    def allowed_models(self) -> list[str]:
        """Allowlist of the active provider as a list; the default model is always allowed."""
        raw = self.anthropic_allowed_models if self.llm_provider == "anthropic" else self.openrouter_allowed_models
        models = [m.strip() for m in raw.split(",") if m.strip()]
        if self.chat_model not in models:
            models.insert(0, self.chat_model)
        return models

    @property
    def reasoning_effort(self) -> str | None:
        """Reasoning effort to send upstream, or None to omit the `reasoning` field entirely."""
        return self.openrouter_reasoning_effort or None


def load_settings() -> Settings:
    return Settings()
