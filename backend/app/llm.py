"""Chat model via OpenRouter (LangChain), configurable by env."""

from functools import lru_cache

from langchain_openai import ChatOpenAI

from app.config import get_settings


@lru_cache
def get_chat_model() -> ChatOpenAI:
    s = get_settings()
    if not s.openrouter_api_key or "your-" in s.openrouter_api_key:
        raise RuntimeError("OPENROUTER_API_KEY is not set (.env)")
    return ChatOpenAI(
        model=s.agent_model,
        base_url=s.openrouter_base_url,
        api_key=s.openrouter_api_key,
        temperature=0,
        max_tokens=1024,
    )
