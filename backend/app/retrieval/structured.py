"""Approach B: structured retrieval + fuzzy part-number matching.

Two operations, designed to be exposed as tools to the LLM (see app/agent/tools.py):

1. `lookup_part_number(text)`: normalizes the part number and resolves it in cascade:
     exact  -> normalized ID identical
     prefix -> normalized ID is a prefix of one or more (partial IDs: '761A', '763X30-11380000')
     fuzzy  -> pg_trgm similarity (typos). These are only *suggestions* ("did you mean…?").
     none   -> nothing similar.
2. `search_actuators(filters)`: the LLM translates the requirements into `SearchFilters`
   (Pydantic schema) and we build parameterized SQL over `actuator_flat`. Comparisons such as
   "torque ≥ X" or "speed < Y" are resolved by the DB, not by the model.

The numbers always come out of `actuator_flat`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.schemas.actuator import normalize_part_number

FUZZY_MIN_SIMILARITY = 0.3
MAX_RESULTS = 10


# ---------------------------------------------------------------- part number lookup

@dataclass
class LookupResult:
    query: str
    normalized: str
    match_type: Literal["exact", "prefix", "fuzzy", "none"]
    candidates: list[dict[str, Any]] = field(default_factory=list)  # actuator_flat rows (+ similarity)

    @property
    def found(self) -> bool:
        """True if the part number resolves to real catalog parts (not just suggestions)."""
        return self.match_type in ("exact", "prefix")


def lookup_part_number(session: Session, part_number: str, limit: int = MAX_RESULTS) -> LookupResult:
    norm = normalize_part_number(part_number)
    if not norm:
        return LookupResult(part_number, norm, "none")

    rows = session.execute(
        text("SELECT * FROM actuator_flat WHERE part_number_normalized = :n"), {"n": norm}
    ).mappings().all()
    if rows:
        return LookupResult(part_number, norm, "exact", [dict(r) for r in rows])

    rows = session.execute(
        text(
            "SELECT * FROM actuator_flat WHERE part_number_normalized LIKE :p "
            "ORDER BY source_table_id, base_part_number LIMIT :k"
        ),
        {"p": norm.replace("%", "").replace("_", "") + "%", "k": limit},
    ).mappings().all()
    if rows:
        return LookupResult(part_number, norm, "prefix", [dict(r) for r in rows])

    rows = session.execute(
        text(
            "SELECT *, similarity(part_number_normalized, :n) AS similarity FROM actuator_flat "
            "WHERE similarity(part_number_normalized, :n) >= :min "
            "ORDER BY similarity DESC, base_part_number LIMIT 3"
        ),
        {"n": norm, "min": FUZZY_MIN_SIMILARITY},
    ).mappings().all()
    if rows:
        return LookupResult(part_number, norm, "fuzzy", [dict(r) for r in rows])
    return LookupResult(part_number, norm, "none")


# ---------------------------------------------------------------- structured search

class SearchFilters(BaseModel):
    """Technical requirements for finding/recommending Series 76 actuators.
    Fill in ONLY what the user asked for; leave everything else as null."""

    enclosure: Literal["weatherproof", "explosionproof"] | None = Field(
        None, description="'explosionproof' for hazardous locations (flammable gas/dust); 'weatherproof' for general/outdoor use."
    )
    voltage_v: int | None = Field(None, description="Supply voltage in volts exactly as requested (e.g. 24, 110, 220, 480).")
    phase: Literal[1, 3] | None = Field(None, description="1 = single phase, 3 = three phase.")
    power_type: Literal["ac", "dc"] | None = Field(None, description="AC or DC supply, only if the user specifies it.")
    service_type: Literal["on_off", "modulating"] | None = Field(
        None, description="'on_off' (open/close) or 'modulating' (positioning/flow control)."
    )
    min_torque: float | None = Field(None, description="Minimum required output torque.")
    max_torque: float | None = Field(None, description="Maximum output torque.")
    torque_unit: Literal["in_lbs", "nm"] = Field("in_lbs", description="Unit of min_torque/max_torque.")
    max_speed_s: int | None = Field(None, description="Maximum operating time for 90° in seconds (inclusive).")
    frequency_hz: Literal[50, 60] | None = Field(None, description="Supply frequency, if specified.")
    max_motor_power_w: int | None = Field(None, description="Maximum motor power in watts.")
    max_fla_a: float | None = Field(None, description="Maximum full load current in amps.")
    csa_certified: bool | None = Field(None, description="true = must be CSA certified; false = only non-CSA models.")
    part_number_prefix: str | None = Field(
        None, description="Partial part number / model code mentioned by the user (e.g. '761B', '764X30')."
    )
    sort_by: Literal["torque_asc", "torque_desc", "speed_asc", "power_asc"] | None = Field(
        None,
        description="torque_asc = smallest model that meets the requirement (right-sizing, default when min_torque is set); "
        "torque_desc = most powerful first; speed_asc = fastest first; power_asc = lowest power first.",
    )
    limit: int = Field(5, ge=1, le=MAX_RESULTS)


@dataclass
class SearchResult:
    filters: SearchFilters
    rows: list[dict[str, Any]]
    sql: str
    params: dict[str, Any]


def _torque_expr(service: str | None, unit: str) -> str:
    u = "nm" if unit == "nm" else "inlb"
    onoff, mod, gen = f"onoff_torque_{u}", f"mod_torque_{u}", f"general_torque_{u}"
    if service == "on_off":
        return f"COALESCE({onoff}, {gen})"
    if service == "modulating":
        return f"COALESCE({mod}, {gen})"
    # No service type: the best torque the actuator offers in any service.
    return f"GREATEST({onoff}, {mod}, {gen})"


def search_actuators(session: Session, f: SearchFilters) -> SearchResult:
    # N/A rows (data_available = false) are NOT excluded: if they match the categorical
    # filters, they are returned last so the agent can say "that model exists but the
    # datasheet has no data" instead of "it does not exist". Any numeric filter excludes
    # them on its own (NULL comparisons).
    where = ["TRUE"]
    p: dict[str, Any] = {}

    if f.enclosure:
        where.append("enclosure = :enclosure"); p["enclosure"] = f.enclosure
    if f.voltage_v is not None:
        where.append("voltage_v = :voltage"); p["voltage"] = f.voltage_v
    if f.phase is not None:
        where.append("phase = :phase"); p["phase"] = f.phase
    if f.power_type:
        where.append("current_type IN (:ptype, 'ac_dc')"); p["ptype"] = f.power_type
    if f.service_type == "on_off":
        where.append("(onoff_torque_inlb IS NOT NULL OR general_torque_inlb IS NOT NULL)")
    elif f.service_type == "modulating":
        where.append("(mod_torque_inlb IS NOT NULL OR general_torque_inlb IS NOT NULL)")
    if f.csa_certified is not None:
        where.append("csa_certified = :csa"); p["csa"] = f.csa_certified
    if f.part_number_prefix:
        where.append("part_number_normalized LIKE :prefix")
        p["prefix"] = normalize_part_number(f.part_number_prefix) + "%"

    torque = _torque_expr(f.service_type, f.torque_unit)
    if f.min_torque is not None:
        where.append(f"{torque} >= :min_torque"); p["min_torque"] = f.min_torque
    if f.max_torque is not None:
        where.append(f"{torque} <= :max_torque"); p["max_torque"] = f.max_torque

    speed = f"speed_{f.frequency_hz}hz_s" if f.frequency_hz else "COALESCE(speed_60hz_s, speed_nominal_s)"
    if f.frequency_hz:
        where.append(f"(speed_{f.frequency_hz}hz_s IS NOT NULL)")
    if f.max_speed_s is not None:
        where.append(f"{speed} <= :max_speed"); p["max_speed"] = f.max_speed_s
    fla = f"fla_{f.frequency_hz}hz_a" if f.frequency_hz else "COALESCE(fla_60hz_a, fla_nominal_a)"
    if f.max_fla_a is not None:
        where.append(f"{fla} <= :max_fla"); p["max_fla"] = f.max_fla_a
    if f.max_motor_power_w is not None:
        where.append("motor_power_w <= :max_power"); p["max_power"] = f.max_motor_power_w

    sort = f.sort_by or (
        "torque_asc" if f.min_torque is not None else "speed_asc" if f.max_speed_s is not None else None
    )
    order = {
        "torque_asc": f"{torque} ASC",
        "torque_desc": f"{torque} DESC",
        "speed_asc": f"{speed} ASC",
        "power_asc": "motor_power_w ASC",
        None: "source_table_id, base_part_number",
    }[sort]

    sql = (
        f"SELECT *, {torque} AS matched_torque FROM actuator_flat WHERE {' AND '.join(where)} "
        f"ORDER BY data_available DESC, {order} NULLS LAST, base_part_number LIMIT :limit"
    )
    p["limit"] = f.limit
    rows = session.execute(text(sql), p).mappings().all()
    return SearchResult(f, [dict(r) for r in rows], sql, p)
