"""FastAPI dependencies: DB session, authenticated user, agent (overridable in tests)."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Annotated, Any

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.api.errors import unauthorized
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


def get_agent() -> Any:
    from app.agent.graph import get_agent as build

    return build()


DbDep = Annotated[Session, Depends(get_db)]
UserDep = Annotated[AppUser, Depends(get_current_user)]
AgentDep = Annotated[Any, Depends(get_agent)]
