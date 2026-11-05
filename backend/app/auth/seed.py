"""Pre-loaded test users (idempotent). They are documented in the README / CLAUDE.md.

Usage: uv run python -m app.auth.seed
"""

from __future__ import annotations

from sqlalchemy import select

from app.auth.security import hash_password
from app.db.models import AppUser
from app.db.session import SessionLocal

TEST_PASSWORD = "Series76!demo"
TEST_USERS = [
    ("ana@konecto.test", "Ana Torres"),
    ("luis@konecto.test", "Luis Gómez"),
]


def seed() -> None:
    with SessionLocal.begin() as db:
        existing = set(db.scalars(select(AppUser.email)))
        created = [
            AppUser(email=email, name=name, password_hash=hash_password(TEST_PASSWORD))
            for email, name in TEST_USERS
            if email not in existing
        ]
        db.add_all(created)
    print(f"Test users: {len(created)} created, {len(TEST_USERS) - len(created)} already existed.")


if __name__ == "__main__":
    seed()
