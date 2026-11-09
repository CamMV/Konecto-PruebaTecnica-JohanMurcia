"""FastAPI dependencies: DB session, authenticated user (and admin), agent (overridable in tests)."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Annotated, Any

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.api.errors import ApiError, unauthorized
from app.auth.security import decode_access_token
from app.db.models import AppUser
from app.db.session import SessionLocal

_bearer = HTTPBearer(auto_error=False)


def get_db() -> Iterator[Session]:
    with SessionLocal() as db:
        yield db


def get_current_user(
    db: Annotated[Session, Depends(get_db)],
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> AppUser:
    if creds is None or creds.scheme.lower() != "bearer":
        raise unauthorized()
    user_id = decode_access_token(creds.credentials)
    user = db.get(AppUser, user_id) if user_id else None
    if user is None:
        raise unauthorized()
    return user


def get_admin_user(user: Annotated[AppUser, Depends(get_current_user)]) -> AppUser:
    if not user.is_admin:
        raise ApiError(403, "ADMIN_REQUIRED", "Only administrators can open the debug data")
    return user


class _UnconfiguredAgent:
    """Stands in for the agent when there is no API key: the request is still validated and the session handled as
    usual, and only the agent call fails, with a clear 503 instead of a generic 500."""

    def invoke(self, *args: Any, **kwargs: Any) -> Any:
        raise ApiError(503, "LLM_NOT_CONFIGURED", "The language model is not configured on the server (OPENROUTER_API_KEY)")


def get_agent() -> Any:
    from app.agent.graph import get_agent as build
    from app.llm import LLMNotConfiguredError

    try:
        return build()
    except LLMNotConfiguredError:
        return _UnconfiguredAgent()


DbDep = Annotated[Session, Depends(get_db)]
UserDep = Annotated[AppUser, Depends(get_current_user)]
AdminDep = Annotated[AppUser, Depends(get_admin_user)]
AgentDep = Annotated[Any, Depends(get_agent)]
