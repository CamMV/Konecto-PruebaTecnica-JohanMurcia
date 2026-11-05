"""Builds ProductCards from the DB (normalized tables). They never use LLM data."""

from __future__ import annotations

from typing import Literal

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.api.schemas import ElectricalOut, PowerSupplyOut, ProductCard, SourceOut
from app.db.models import ActuatorRow, SourceTableRow
from app.schemas.actuator import ServiceRating

_SERVICE_ORDER = {"on_off": 0, "modulating": 1, "general": 2}
_FREQ_ORDER = {60: 0, 50: 1, None: 2}


def power_label(voltage_v: int, phase: int | None, current_type: str) -> str:
    if phase == 1:
        return f"{voltage_v}V single phase AC"
    if phase == 3:
        return f"{voltage_v}V three phase AC"
    return f"{voltage_v}V {'AC/DC' if current_type == 'ac_dc' else 'DC'}"


def build_cards(
    db: Session, part_numbers: list[str], role: Literal["match", "alternative"] = "match"
) -> list[ProductCard]:
    """Cards in the order given. Part numbers that don't exist are ignored."""
    if not part_numbers:
        return []
    rows = db.scalars(
        select(ActuatorRow)
        .where(ActuatorRow.base_part_number.in_(part_numbers))
        .options(selectinload(ActuatorRow.ratings), selectinload(ActuatorRow.electrical))
    ).all()
    tables = {t.id: t for t in db.scalars(select(SourceTableRow))}
    by_pn = {r.base_part_number: r for r in rows}

    cards = []
    for pn in dict.fromkeys(part_numbers):
        a = by_pn.get(pn)
        if a is None:
            continue
        src = tables[a.source_table_id]
        cards.append(
            ProductCard(
                part_number=a.base_part_number,
                role=role,
                enclosure=a.enclosure,
                certifications=a.certifications,
                csa_certified=a.csa_certified,
                power_supply=PowerSupplyOut(
                    voltage_v=a.voltage_v,
                    phase=a.phase,
                    current_type=a.current_type,
                    label=power_label(a.voltage_v, a.phase, a.current_type),
                ),
                motor_power_w=a.motor_power_w,
                data_available=a.data_available,
                ratings=[
                    ServiceRating(
                        service_type=r.service_type,
                        torque_inlb=r.torque_inlb,
                        torque_nm=r.torque_nm,
                        duty_cycle_pct=r.duty_cycle_pct,
                        cycles_per_hour=r.cycles_per_hour,
                        starts_per_hour=r.starts_per_hour,
                    )
                    for r in sorted(a.ratings, key=lambda r: _SERVICE_ORDER[r.service_type])
                ],
                electrical=[
                    ElectricalOut(
                        frequency_hz=e.frequency_hz,
                        operating_speed_s=e.operating_speed_s,
                        fla_a=e.fla_a,
                        lra_a=e.lra_a,
                    )
                    for e in sorted(a.electrical, key=lambda e: _FREQ_ORDER[e.frequency_hz])
                ],
                source=SourceOut(table=src.title, page=src.page),
            )
        )
    return cards
