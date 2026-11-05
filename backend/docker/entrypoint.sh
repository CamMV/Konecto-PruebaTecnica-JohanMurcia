#!/bin/sh
# Backend startup: migrations → test users → catalog ingestion (only if empty) → API.
set -e
cd /app/backend

alembic upgrade head
python -m app.auth.seed

COUNT=$(python -c "from sqlalchemy import text; from app.db.session import SessionLocal; print(SessionLocal().scalar(text('SELECT count(*) FROM actuator')))")
if [ "$COUNT" = "0" ]; then
  echo "Empty catalog: loading data/processed/actuators.json"
  python -m pipeline.ingest
else
  echo "Catalog already loaded ($COUNT actuators)"
fi

exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers
