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


class Settings(BaseSettings):
    """All tunables of the server. Every field maps to an env var of the same (upper-case) name."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # OpenRouter (chat)
    openrouter_api_key: str = ""
    openrouter_model: str = "openai/gpt-5.6-luna"
    openrouter_reasoning_effort: str = "high"
    openrouter_allowed_models: str = DEFAULT_ALLOWED_MODELS
    openrouter_base_url: str = "https://openrouter.ai/api/v1"

    # Identity and storage
    server_name: str = "FullSkat"
    data_dir: Path = Path("./data")

    # Speech to text
    stt_backend: Literal["openrouter", "faster_whisper", "openai"] = "openrouter"
    stt_openrouter_model: str = "google/gemini-3.5-flash-lite"
    stt_openai_base_url: str = "https://api.openai.com/v1"
    stt_openai_api_key: str = ""
    stt_openai_model: str = "whisper-large-v3-turbo"
    whisper_model: str = "large-v3-turbo"
    whisper_device: str = "auto"
    whisper_compute_type: str = "auto"

    # Listening session timing
    partial_interval_ms: int = Field(default=1000, ge=100)
    silence_ms: int = Field(default=5000, ge=100)
    no_speech_timeout_s: int = Field(default=30, ge=1)
    max_utterance_s: int = Field(default=60, ge=1)

    # Rate limits
    pair_per_min: int = Field(default=5, ge=1)
    pair_lock_failures: int = Field(default=10, ge=1)
    pair_lock_minutes: int = Field(default=15, ge=1)
    chat_per_min: int = Field(default=20, ge=1)
    chat_per_day: int = Field(default=500, ge=1)
    listen_per_min: int = Field(default=40, ge=1)
    listen_per_day: int = Field(default=1000, ge=1)

    # Chat shaping
    max_history_messages: int = Field(default=24, ge=1)
    max_history_chars: int = Field(default=12000, ge=1)
    max_completion_tokens: int = Field(default=700, ge=1)

    # Networking / misc
    trust_proxy_headers: bool = False
    log_level: str = "INFO"

    @field_validator("openrouter_reasoning_effort", mode="before")
    @classmethod
    def _blank_effort_means_none(cls, value: object) -> str:
        return "" if value is None else str(value).strip()

    @property
    def allowed_models(self) -> list[str]:
        """Allowlist as a list; the default model is always allowed."""
        models = [m.strip() for m in self.openrouter_allowed_models.split(",") if m.strip()]
        if self.openrouter_model not in models:
            models.insert(0, self.openrouter_model)
        return models

    @property
    def reasoning_effort(self) -> str | None:
        """Reasoning effort to send upstream, or None to omit the `reasoning` field entirely."""
        return self.openrouter_reasoning_effort or None


def load_settings() -> Settings:
    return Settings()
