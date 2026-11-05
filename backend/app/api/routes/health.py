from fastapi import APIRouter
from sqlalchemy import text

from app.api.schemas import Health
from app.db.session import SessionLocal

router = APIRouter(tags=["health"])


@router.get("/health", response_model=Health)
def health() -> Health:
    try:
        with SessionLocal() as db:
            n = db.scalar(text("SELECT count(*) FROM actuator"))
        return Health(status="ok" if n else "degraded", db=True, actuators=n or 0)
    except Exception:  # noqa: BLE001
        return Health(status="degraded", db=False, actuators=0)
