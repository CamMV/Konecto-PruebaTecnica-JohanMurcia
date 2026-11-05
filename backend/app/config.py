"""Settings loaded from environment variables (and the repo-root .env file)."""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

REPO_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = REPO_ROOT / "data"
RAW_DIR = REPO_ROOT / "docs" / "data"
PROCESSED_DIR = DATA_DIR / "processed"
INTERIM_DIR = DATA_DIR / "interim"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=REPO_ROOT / ".env", extra="ignore")

    database_url: str = "postgresql+psycopg://konecto:konecto@localhost:5432/konecto"

    google_api_key: str | None = None
    extraction_model: str = "gemini-3.6-flash"

    openrouter_api_key: str | None = None
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    # Agent model (tool calling). Also used by the approach B eval to pick the tool and its arguments.
    agent_model: str = "anthropic/claude-haiku-5.5"

    agent_max_model_calls: int = 6  # LLM calls per turn (cost guard)
    agent_max_tool_calls: int = 5  # tool calls per turn
    agent_timeout_s: int = 60
    history_turns: int = 8  # previous turns sent to the agent

    # --- API / auth
    jwt_secret: str = "dev-only-change-me"
    jwt_expire_minutes: int = 1440
    debug_api_enabled: bool = True
    cors_origins: str = "*"

    # "openrouter" (default, any embedding model on OpenRouter) or "google" (Gemini, free tier).
    embedding_provider: str = "openrouter"
    embedding_model: str = "openai/text-embedding-3-small"
    embedding_dim: int = 1536


@lru_cache
def get_settings() -> Settings:
    return Settings()
