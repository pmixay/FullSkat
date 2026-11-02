"""Server settings, loaded from environment variables (see server/.env.example)."""

from __future__ import annotations

from pathlib import Path
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_ALLOWED_MODELS = "openai/gpt-6.1-terra,openai/gpt-6-luna"
# Kimi Code model ids (https://www.kimi.com/code/docs/en/); which ones work depends on the membership plan.
DEFAULT_KIMI_ALLOWED_MODELS = "k3.1-pro,k3,kimi-for-coding"
# Efforts documented for Kimi Code: K3 and kimi-for-coding take low/high/max, K3.1 Pro always thinks (max).
DEFAULT_KIMI_MODEL_EFFORTS = {"k3": "low,high,max", "k3-256k": "low,high,max", "kimi-for-coding": "low,high,max", "k3.1-pro": ""}
DEFAULT_ANTHROPIC_ALLOWED_MODELS = "claude-haiku-5-5,claude-sonnet-5-5,claude-opus-5-5,claude-fable-5-1"


PROVIDERS = ("anthropic", "openrouter", "kimi")


class Settings(BaseSettings):
    """All tunables of the server. Every field maps to an env var of the same (upper-case) name."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # Chat providers are not pinned: every provider with an API key set is probed (see llm.MultiLLM) and
    # its models are offered to the watch for as long as the probe succeeds. Priority: anthropic, openrouter, kimi.
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

    # OpenRouter (chat)
    openrouter_api_key: str = ""
    openrouter_model: str = "openai/gpt-6.1-terra"
    openrouter_reasoning_effort: str = "high"
    openrouter_allowed_models: str = DEFAULT_ALLOWED_MODELS
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    # Upper bound for one answer including reasoning tokens (a small cap lets reasoning eat the whole answer).
    openrouter_max_tokens: int = Field(default=4096, ge=1)

    # Kimi Code platform (chat, OpenAI-compatible API of the Kimi Code membership, with an API key from the
    # Kimi Code console). Overseas: https://api.kimi.ai/coding/v1, mainland China: https://api.kimi.com/coding/v1
    kimi_api_key: str = ""
    kimi_model: str = "k3.1-pro"
    kimi_allowed_models: str = DEFAULT_KIMI_ALLOWED_MODELS
    kimi_base_url: str = "https://api.kimi.ai/coding/v1"
    # Upper bound for one answer including thinking (K3.1 Pro always thinks, at max effort by default).
    kimi_max_tokens: int = Field(default=8192, ge=1)
    # Effort levels per Kimi model, since they differ and the API has no endpoint that lists them. In order:
    # KIMI_MODEL_EFFORTS (JSON, per model; "" = not adjustable; the default follows Kimi Code's docs),
    # OpenRouter's public model list (moonshotai/<model>, only with KIMI_EFFORTS_FROM_CATALOG=true; its model
    # ids and rules are those of the Moonshot API, not Kimi Code) and KIMI_EFFORTS (comma-separated, e.g.
    # "low,high"), the list for every model without its own; empty = not adjustable.
    kimi_model_efforts: dict[str, str] = Field(default_factory=lambda: dict(DEFAULT_KIMI_MODEL_EFFORTS))
    kimi_efforts_from_catalog: bool = False
    kimi_efforts: str = ""
    kimi_default_effort: str = ""

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

    # Networking / misc
    trust_proxy_headers: bool = False
    log_level: str = "INFO"

    @field_validator("openrouter_reasoning_effort", "anthropic_effort", mode="before")
    @classmethod
    def _blank_effort_means_none(cls, value: object) -> str:
        return "" if value is None else str(value).strip()

    @property
    def configured_providers(self) -> list[str]:
        """Providers that have an API key, in priority order. Whether they actually work is probed at runtime."""
        keys = {"anthropic": self.anthropic_api_key, "openrouter": self.openrouter_api_key, "kimi": self.kimi_api_key}
        return [name for name in PROVIDERS if keys[name].strip()]

    def provider_default_model(self, provider: str) -> str:
        return {"anthropic": self.anthropic_model, "openrouter": self.openrouter_model, "kimi": self.kimi_model}[provider]

    def provider_models(self, provider: str) -> list[str]:
        """Allowlist of one provider; its default model is always allowed and listed first if missing."""
        raw = {
            "anthropic": self.anthropic_allowed_models,
            "openrouter": self.openrouter_allowed_models,
            "kimi": self.kimi_allowed_models,
        }[provider]
        models = _split(raw)
        default = self.provider_default_model(provider)
        if default not in models:
            models.insert(0, default)
        return models

    def provider_for(self, model: str) -> str | None:
        """The provider that serves `model` (the first one, in priority order, whose list has it), or None."""
        for provider in PROVIDERS:
            if model in self.provider_models(provider):
                return provider
        return None

    @property
    def reasoning_effort(self) -> str | None:
        """Reasoning effort to send upstream, or None to omit the `reasoning` field entirely."""
        return self.openrouter_reasoning_effort or None


def _split(raw: str) -> list[str]:
    return [item.strip() for item in raw.split(",") if item.strip()]


def load_settings() -> Settings:
    return Settings()
