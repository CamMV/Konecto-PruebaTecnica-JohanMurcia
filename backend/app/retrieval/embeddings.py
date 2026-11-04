"""Embeddings provider (LangChain), configurable by env."""

from __future__ import annotations

from functools import lru_cache

from langchain_core.embeddings import Embeddings

from app.config import get_settings


@lru_cache
def get_embeddings() -> Embeddings:
    s = get_settings()
    if s.embedding_provider == "google":
        from langchain_google_genai import GoogleGenerativeAIEmbeddings

        if not s.google_api_key:
            raise RuntimeError("EMBEDDING_PROVIDER=google requires GOOGLE_API_KEY")
        return GoogleGenerativeAIEmbeddings(
            model=s.embedding_model, google_api_key=s.google_api_key, output_dimensionality=s.embedding_dim
        )

    from langchain_openai import OpenAIEmbeddings

    if not s.openrouter_api_key or "your-" in s.openrouter_api_key:
        raise RuntimeError("OPENROUTER_API_KEY is not set (.env)")
    return OpenAIEmbeddings(
        model=s.embedding_model,
        base_url=s.openrouter_base_url,
        api_key=s.openrouter_api_key,
        dimensions=s.embedding_dim if s.embedding_model.startswith("openai/text-embedding-3") else None,
        # OpenRouter expects text, not the token arrays that langchain sends by default.
        check_embedding_ctx_length=False,
    )
