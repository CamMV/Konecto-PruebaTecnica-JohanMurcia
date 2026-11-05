"""Password hashing (argon2 via pwdlib) and JWT tokens (HS256 via PyJWT)."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import jwt
from pwdlib import PasswordHash

from app.config import get_settings

_hasher = PasswordHash.recommended()  # argon2id
ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    return _hasher.verify(password, password_hash)


def create_access_token(user_id: uuid.UUID) -> tuple[str, int]:
    """Returns (token, expires_in seconds)."""
    s = get_settings()
    expires_in = s.jwt_expire_minutes * 60
    now = datetime.now(UTC)
    payload = {"sub": str(user_id), "iat": now, "exp": now + timedelta(seconds=expires_in)}
    return jwt.encode(payload, s.jwt_secret, algorithm=ALGORITHM), expires_in


def decode_access_token(token: str) -> uuid.UUID | None:
    """Returns the user_id, or None if the token is invalid or expired."""
    try:
        payload = jwt.decode(token, get_settings().jwt_secret, algorithms=[ALGORITHM])
        return uuid.UUID(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        return None
