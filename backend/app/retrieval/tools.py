"""LangChain tools for approach B. These are the tools the agent will use in the backend.

They return dicts with the data straight from `actuator_flat` (the numbers are never generated
by the LLM) plus metadata (match_type, applied filters) for the debug screen.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any

from langchain_core.tools import tool

from app.db.session import SessionLocal
from app.retrieval import structured
from app.retrieval.structured import SearchFilters

# Internal columns that are of no use to the LLM.
_HIDDEN = {"part_number_normalized", "source_table_id", "matched_torque"}


def _clean(row: dict[str, Any]) -> dict[str, Any]:
    return {
        k: float(v) if isinstance(v, Decimal) else v
        for k, v in row.items()
        if k not in _HIDDEN and v is not None
    }


@tool
def lookup_actuator(part_number: str) -> dict:
    """Look up a Series 76 actuator by its Base Part Number (full or partial, any formatting,
    e.g. '761A00-11300000/A', '761a0011300000a', '761A'). Use it whenever the user mentions
    a specific part number or model code and no other requirement. Returns match_type:
    'exact' (found), 'prefix' (partial ID: every model that starts like that), 'fuzzy'
    (NOT found: similar suggestions only, likely a typo) or 'none' (does not exist)."""
    with SessionLocal() as session:
        r = structured.lookup_part_number(session, part_number)
    return {
        "query": part_number,
        "match_type": r.match_type,
        "found": r.found,
        "results": [_clean(c) for c in r.candidates],
    }


@tool(args_schema=SearchFilters)
def search_actuators(**filters: Any) -> dict:
    """Find and recommend Series 76 actuators that meet technical requirements (enclosure,
    voltage, phase, AC/DC, on/off or modulating service, torque, speed, current, power,
    certification, partial model code). Use it when the user describes requirements, or
    asks for the highest/lowest/fastest model. An empty result means no model meets ALL
    the requirements."""
    f = SearchFilters(**filters)
    with SessionLocal() as session:
        r = structured.search_actuators(session, f)
    return {
        "filters": f.model_dump(exclude_none=True),
        "count": len(r.rows),
        "results": [_clean(row) for row in r.rows],
    }


TOOLS = [lookup_actuator, search_actuators]
