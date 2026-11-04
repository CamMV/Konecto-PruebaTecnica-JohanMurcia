"""Builds `search_document` (approach A): one document per part number + context documents.

The documents are rendered from `actuator_flat` (what is loaded in the DB), so the index
always matches the data. Embeddings use EMBEDDING_PROVIDER / EMBEDDING_MODEL.

Usage:
    uv run python -m pipeline.build_index                  # lexical + embeddings
    uv run python -m pipeline.build_index --no-embeddings  # lexical only (no API key)
"""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass

from sqlalchemy import text

from app.config import PROCESSED_DIR, get_settings
from app.db.models import SearchDocument
from app.db.session import SessionLocal
from app.retrieval.text import lexical_tokens

SUMMARY_MD = PROCESSED_DIR / "series_76_summary.md"

ENCLOSURE_TEXT = {
    "weatherproof": "Weatherproof enclosure for general industrial use, protected against dust, rain and "
    "environmental elements (outdoor).",
    "explosionproof": "Explosionproof enclosure for hazardous locations where flammable gases or dust may be "
    "present (hazardous area).",
}


@dataclass
class Doc:
    base_part_number: str | None
    doc_type: str
    title: str
    content: str


def n(v) -> str:
    return f"{v:,}" if isinstance(v, int) else str(v)


def supply_text(r) -> str:
    if r.phase == 1:
        return f"{r.voltage_v}V single phase AC"
    if r.phase == 3:
        return f"{r.voltage_v}V three phase (3 phase) AC"
    return f"{r.voltage_v}V {'AC/DC' if r.current_type == 'ac_dc' else 'DC'}"


def render_actuator(r) -> Doc:
    title = f"{r.base_part_number}: {r.enclosure.capitalize()} {supply_text(r)} electric actuator"
    parts = [
        f"Series 76 electric actuator, base part number {r.base_part_number}, model {r.model_code}, size {r.size}.",
        ENCLOSURE_TEXT[r.enclosure],
        f"Certifications: {', '.join(r.certifications)}." + ("" if r.csa_certified else " Not CSA certified."),
        f"Power supply: {supply_text(r)}.",
    ]
    if not r.data_available:
        parts.append("Torque and electrical data not available (N/A) for this configuration in the datasheet.")
        return Doc(r.base_part_number, "actuator", title, " ".join(parts))

    if r.onoff_torque_inlb:
        parts.append(
            f"On/Off service: output torque {n(r.onoff_torque_inlb)} in-lbs ({n(r.onoff_torque_nm)} Nm), "
            f"duty cycle {r.onoff_duty_cycle_pct}%, {r.onoff_cycles_per_hour} cycles per hour."
        )
    if r.mod_torque_inlb:
        parts.append(
            f"Modulating service: output torque {n(r.mod_torque_inlb)} in-lbs ({n(r.mod_torque_nm)} Nm), "
            f"duty cycle {r.mod_duty_cycle_pct}%, {r.mod_starts_per_hour} starts per hour."
        )
    if r.general_torque_inlb:
        parts.append(
            f"Output torque {n(r.general_torque_inlb)} in-lbs ({n(r.general_torque_nm)} Nm), "
            f"duty cycle {r.general_duty_cycle_pct}%, {r.general_cycles_per_hour} cycles per hour, "
            f"{r.general_starts_per_hour} starts per hour."
        )
    if r.speed_60hz_s:
        parts.append(
            f"Operating speed {r.speed_60hz_s} s at 60 Hz and {r.speed_50hz_s} s at 50 Hz. "
            f"Full load current {r.fla_60hz_a} A at 60 Hz, {r.fla_50hz_a} A at 50 Hz. "
            f"Locked rotor current {r.lra_60hz_a} A at 60 Hz, {r.lra_50hz_a} A at 50 Hz."
        )
    if r.speed_nominal_s:
        parts.append(
            f"Operating speed {r.speed_nominal_s} s. Full load current {r.fla_nominal_a} A. "
            f"Locked rotor current {r.lra_nominal_a} A."
        )
    if r.motor_power_w:
        parts.append(f"Motor power {r.motor_power_w} W.")
    return Doc(r.base_part_number, "actuator", title, " ".join(parts))


def context_docs(session) -> list[Doc]:
    docs: list[Doc] = []
    if SUMMARY_MD.exists():
        md = SUMMARY_MD.read_text()
        for section in re.split(r"\n(?=## )", md):
            lines = [ln for ln in section.strip().splitlines() if not ln.startswith(">")]
            title = lines[0].lstrip("# ").strip()
            body = "\n".join(lines[1:]).strip()
            if body:
                docs.append(Doc(None, "context", f"Series 76 overview: {title}", body))
    for t in session.execute(text("SELECT title, notes FROM source_table ORDER BY page, id")).all():
        if t.notes:
            docs.append(Doc(None, "context", f"Datasheet notes: {t.title}", "\n".join(t.notes)))
    return docs


def run(embeddings: bool = True) -> None:
    settings = get_settings()
    with SessionLocal.begin() as session:
        rows = session.execute(text("SELECT * FROM actuator_flat ORDER BY source_table_id, base_part_number")).all()
        if not rows:
            raise SystemExit("actuator_flat is empty: run `python -m pipeline.ingest` first.")
        docs = [render_actuator(r) for r in rows] + context_docs(session)

        vectors: list[list[float] | None] = [None] * len(docs)
        if embeddings:
            from app.retrieval.embeddings import get_embeddings

            vectors = get_embeddings().embed_documents([f"{d.title}\n{d.content}" for d in docs])
            if len(vectors[0]) != settings.embedding_dim:
                raise SystemExit(
                    f"The model returned {len(vectors[0])} dimensions but EMBEDDING_DIM={settings.embedding_dim}."
                )

        session.execute(text("TRUNCATE search_document RESTART IDENTITY"))
        session.add_all(
            SearchDocument(
                base_part_number=d.base_part_number,
                doc_type=d.doc_type,
                title=d.title,
                content=d.content,
                lexical_text=" ".join(lexical_tokens(f"{d.title} {d.content}")),
                embedding=v,
                embedding_model=settings.embedding_model if v is not None else None,
            )
            for d, v in zip(docs, vectors)
        )
    n_act = sum(d.doc_type == "actuator" for d in docs)
    print(
        f"Index: {n_act} actuator documents + {len(docs) - n_act} context documents "
        f"({'with embeddings: ' + settings.embedding_model if embeddings else 'without embeddings'})."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-embeddings", action="store_true", help="lexical only (no API key needed)")
    args = parser.parse_args()
    run(embeddings=not args.no_embeddings)


if __name__ == "__main__":
    main()
