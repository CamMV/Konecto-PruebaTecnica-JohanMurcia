"""Loads the catalog into PostgreSQL. Idempotent: replaces all the data inside a single transaction.

    uv run python -m pipeline.ingest --from-md      # md -> actuators.json -> validate -> DB
    uv run python -m pipeline.ingest                # actuators.json (already generated) -> validate -> DB
    uv run python -m pipeline.ingest --with-index   # also rebuilds the retrieval index

Needs no API keys (except --with-index, which computes embeddings).
Applies the Alembic migrations before loading.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import text

from app.db.models import ActuatorRow, ElectricalRow, RatingRow, SourceTableRow
from app.db.session import SessionLocal
from app.schemas.actuator import Catalog
from pipeline import parse_markdown, validate

BACKEND_DIR = Path(__file__).resolve().parents[1]


def migrate() -> None:
    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND_DIR / "alembic"))
    command.upgrade(cfg, "head")


def load(catalog: Catalog) -> None:
    with SessionLocal.begin() as session:
        # CASCADE also empties search_document: the index must be rebuilt afterwards.
        session.execute(text("TRUNCATE source_table, actuator, actuator_rating, actuator_electrical, search_document CASCADE"))
        session.add_all(
            SourceTableRow(
                id=t.table_id,
                page=t.page,
                title=t.title,
                enclosure=t.enclosure.value,
                certifications=t.certifications,
                power_desc=t.power_desc,
                notes=t.notes,
            )
            for t in catalog.tables
        )
        session.flush()
        for a in catalog.actuators:
            session.add(
                ActuatorRow(
                    base_part_number=a.base_part_number,
                    part_number_normalized=a.part_number_normalized,
                    model_code=a.model_code,
                    size=a.size,
                    enclosure=a.enclosure.value,
                    certifications=a.certifications,
                    csa_certified=a.csa_certified,
                    voltage_v=a.voltage_v,
                    phase=a.phase,
                    current_type=a.current_type.value,
                    motor_power_w=a.motor_power_w,
                    data_available=a.data_available,
                    source_table_id=a.source.table_id,
                    source_row=a.source.row_index,
                    ratings=[RatingRow(**r.model_dump(mode="python") | {"service_type": r.service_type.value}) for r in a.ratings],
                    electrical=[ElectricalRow(**e.model_dump(mode="python")) for e in a.electrical],
                )
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from-md", action="store_true", help="regenerate actuators.json from the .md first")
    parser.add_argument("--md", type=Path, default=parse_markdown.DEFAULT_MD)
    parser.add_argument("--json", type=Path, default=parse_markdown.DEFAULT_JSON)
    parser.add_argument("--with-index", action="store_true", help="rebuild search_document + embeddings")
    args = parser.parse_args()

    if args.from_md:
        catalog = parse_markdown.parse(args.md.read_text())
    else:
        catalog = Catalog.model_validate_json(args.json.read_text())

    if not validate.report(validate.validate(catalog)):
        sys.exit("Validation failed: nothing was loaded.")
    if args.from_md:
        parse_markdown.write_catalog(catalog, args.json)
        print(f"Updated {args.json}")

    migrate()
    load(catalog)
    print(f"Loaded: {len(catalog.tables)} tables, {len(catalog.actuators)} actuators.")

    if args.with_index:
        from pipeline import build_index

        build_index.run()
    else:
        print("Note: search_document was emptied. Run `python -m pipeline.build_index`.")


if __name__ == "__main__":
    main()
