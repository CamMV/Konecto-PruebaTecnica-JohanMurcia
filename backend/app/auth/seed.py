"""Pre-loaded test users (idempotent). They are documented in the README / CLAUDE.md.

Ana is an administrator (she can open the debug screens); Luis is a regular user. Re-running the seed keeps the
admin flag of these test users in sync, so an existing DB gets the roles too.

Usage: uv run python -m app.auth.seed
"""

from __future__ import annotations

from sqlalchemy import select

from app.auth.security import hash_password
from app.db.models import AppUser
from app.db.session import SessionLocal

TEST_PASSWORD = "Series76!demo"
TEST_USERS = [
    # (email, name, is_admin)
    ("ana@konecto.test", "Ana Torres", True),
    ("luis@konecto.test", "Luis Gómez", False),
]


def seed() -> None:
    with SessionLocal.begin() as db:
        existing = {u.email: u for u in db.scalars(select(AppUser))}
        created = 0
        for email, name, is_admin in TEST_USERS:
            if user := existing.get(email):
                user.is_admin = is_admin
            else:
                db.add(AppUser(email=email, name=name, password_hash=hash_password(TEST_PASSWORD), is_admin=is_admin))
                created += 1
    print(f"Test users: {created} created, {len(TEST_USERS) - created} already existed (admin flags synced).")


if __name__ == "__main__":
    seed()
