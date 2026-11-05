"""The agent's LangChain tools (approach B: structured + fuzzy ID).

They use `response_format="content_and_artifact"`:
  - content  -> compact JSON the LLM reads (data straight from `actuator_flat`).
  - artifact -> the same payload + `duration_ms`; the service uses it for the debug trace and to
                know which parts came out of the DB in this turn (product cards are built only from those).
The LLM never generates the numbers: it copies them from here.
"""

from __future__ import annotations

import json
import time
from decimal import Decimal
from typing import Any

from langchain_core.tools import tool

from app.db.session import SessionLocal
from app.retrieval import structured
from app.retrieval.structured import SearchFilters

# Internal columns that are of no use to the LLM.
_HIDDEN = {"part_number_normalized", "source_table_id", "matched_torque", "source_title", "source_page"}


def _clean(row: dict[str, Any]) -> dict[str, Any]:
    return {
        k: float(v) if isinstance(v, Decimal) else v
        for k, v in row.items()
        if k not in _HIDDEN and v is not None
    }


def _respond(payload: dict, started: float) -> tuple[str, dict]:
    content = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
    return content, payload | {"duration_ms": round((time.perf_counter() - started) * 1000)}


@tool(response_format="content_and_artifact")
def lookup_actuator(part_number: str) -> tuple[str, dict]:
    """Look up a Series 76 actuator by its Base Part Number (full or partial, any formatting,
    e.g. '761A00-11300000/A', '761a0011300000a', '761A'). Use it whenever the user mentions
    a specific part number or model code and no other requirement. Returns match_type:
    'exact' (found), 'prefix' (partial ID: every model that starts like that), 'fuzzy'
    (NOT found: similar suggestions only, likely a typo) or 'none' (does not exist)."""
    t0 = time.perf_counter()
    with SessionLocal() as session:
        r = structured.lookup_part_number(session, part_number)
    return _respond(
        {
            "query": part_number,
            "match_type": r.match_type,
            "found": r.found,
            "results": [_clean(c) for c in r.candidates],
        },
        t0,
    )


@tool(args_schema=SearchFilters, response_format="content_and_artifact")
def search_actuators(**filters: Any) -> tuple[str, dict]:
    """Find and recommend Series 76 actuators that meet technical requirements (enclosure,
    voltage, phase, AC/DC, on/off or modulating service, torque, speed, current, power,
    certification, partial model code). Use it when the user describes requirements, or
    asks for the highest/lowest/fastest model. An empty result means no model meets ALL
    the requirements."""
    t0 = time.perf_counter()
    f = SearchFilters(**filters)
    with SessionLocal() as session:
        r = structured.search_actuators(session, f)
    return _respond(
        {
            "filters": f.model_dump(exclude_none=True),
            "count": len(r.rows),
            "results": [_clean(row) for row in r.rows],
        },
        t0,
    )


TOOLS = [lookup_actuator, search_actuators]
