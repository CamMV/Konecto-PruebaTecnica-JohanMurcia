"""FastAPI app: Series 76 Technical Assistant.

    uv run uvicorn app.main:app --reload       (from backend/)
    OpenAPI: http://localhost:8000/docs
"""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import errors
from app.api.routes import auth, conversation, debug, health, sessions
from app.config import get_settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(
    title="Series 76 Technical Assistant API",
    version="1.0.0",
    description="Contract: docs/api_contract.md. Authentication: Bearer token from POST /api/auth/login.",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o.strip() for o in get_settings().cors_origins.split(",")],
    allow_methods=["*"],
    allow_headers=["*"],
)
errors.install(app)
for r in (auth.router, conversation.router, sessions.router, debug.router, health.router):
    app.include_router(r, prefix="/api")
