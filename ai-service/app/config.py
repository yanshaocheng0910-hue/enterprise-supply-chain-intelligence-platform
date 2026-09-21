"""Environment-backed settings for the AI service."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal
from urllib.parse import urlparse

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        # Support both `uvicorn --app-dir ai-service` from D:\论文 and direct
        # execution from inside ai-service.
        env_file=(str(Path(__file__).resolve().parents[1] / ".env"), ".env"),
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # A token is always required by the protected endpoints.  The development
    # default is intentionally obvious and must be replaced in any shared or
    # deployed environment.
    service_token: str = Field(
        default="dev-service-token",
        validation_alias=AliasChoices("X_SERVICE_TOKEN", "SERVICE_TOKEN"),
    )
    llm_provider: Literal["rule", "openai-compatible"] = Field(
        default="rule",
        validation_alias=AliasChoices("LLM_PROVIDER", "AI_PROVIDER"),
    )
    openai_base_url: str | None = Field(
        default=None,
        validation_alias=AliasChoices("OPENAI_BASE_URL", "LLM_BASE_URL"),
    )
    openai_api_key: str | None = Field(
        default=None,
        validation_alias=AliasChoices("OPENAI_API_KEY", "LLM_API_KEY"),
    )
    openai_model: str | None = Field(
        default=None,
        validation_alias=AliasChoices("OPENAI_MODEL", "LLM_MODEL"),
    )
    openai_timeout_seconds: float = Field(
        default=60.0,
        validation_alias=AliasChoices("OPENAI_TIMEOUT_SECONDS", "LLM_TIMEOUT_SECONDS"),
        ge=0.5,
        le=120,
    )
    openai_max_tokens: int = Field(
        default=512,
        validation_alias=AliasChoices("OPENAI_MAX_TOKENS", "LLM_MAX_TOKENS"),
        ge=128,
        le=2048,
    )
    llm_local_only: bool = Field(
        default=True,
        validation_alias=AliasChoices("LLM_LOCAL_ONLY", "AI_LOCAL_ONLY"),
        description="默认只允许回环地址上的本地模型，防止业务数据误发到外部 API",
    )
    cors_allow_origins: str = Field(
        default="http://localhost:5173",
        validation_alias=AliasChoices("CORS_ALLOW_ORIGINS"),
    )
    log_level: str = Field(default="INFO", validation_alias=AliasChoices("LOG_LEVEL"))
    service_name: str = Field(default="ai-service", validation_alias=AliasChoices("SERVICE_NAME"))
    service_version: str = Field(default="0.1.0", validation_alias=AliasChoices("SERVICE_VERSION"))

    @field_validator("llm_provider", mode="before")
    @classmethod
    def normalise_provider_name(cls, value: str) -> str:
        if isinstance(value, str) and value.strip().lower() in {"openai_compatible", "openai-compatible", "openai"}:
            return "openai-compatible"
        return value

    @property
    def provider_configured(self) -> bool:
        """Whether an explicit, complete OpenAI-compatible setup is available."""

        return bool(
            self.llm_provider == "openai-compatible"
            and self.openai_base_url
            and self.openai_api_key
            and self.openai_model
            and self.provider_endpoint_allowed
        )

    @property
    def provider_endpoint_allowed(self) -> bool:
        if not self.llm_local_only:
            return True
        if not self.openai_base_url:
            return False
        try:
            hostname = (urlparse(self.openai_base_url).hostname or "").lower()
        except ValueError:
            return False
        return hostname in {"127.0.0.1", "localhost", "::1"}

    @property
    def provider_configuration_warning(self) -> str | None:
        if self.llm_provider == "rule":
            return "未配置 OpenAI-compatible provider，使用可复现规则解析"
        if self.openai_base_url and not self.provider_endpoint_allowed:
            return "本地模型安全策略已拒绝外部 LLM 地址，未发送业务文本并改用规则解析"
        if not self.provider_configured:
            return "OpenAI-compatible provider 配置不完整，使用可复现规则解析"
        return None

    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allow_origins.split(",") if origin.strip()]


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
