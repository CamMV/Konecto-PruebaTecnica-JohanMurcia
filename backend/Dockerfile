# Backend image. Build context = repo root (it needs data/processed for the initial ingestion).
#   docker compose build backend
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PYTHONUNBUFFERED=1 \
    PATH="/app/backend/.venv/bin:$PATH"

WORKDIR /app/backend

# Dependencies first (layer cache)
COPY backend/pyproject.toml backend/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

# Code + processed data (the reviewed source of truth; ingestion needs no API keys)
COPY backend/ ./
COPY data/processed/ /app/data/processed/
RUN chmod +x docker/entrypoint.sh

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/api/health').status==200 else 1)"
ENTRYPOINT ["docker/entrypoint.sh"]
