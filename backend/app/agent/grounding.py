"""Numeric grounding check: every number in the answer must come from the data.

Allowed sources: tool results from the turn, the user's query, the history sent to the
agent (already validated in its own turn) and a small allowlist (50/60 Hz, 90°, S4, 1/3 phase).
The product name "Series 76" is ignored.
Digits that are part of a part number or a model code are ignored ('761A00-11300000/A', '761A').
Markdown list markers ("1. ") are ignored too.

Known limitation: it validates that the number *exists* in the data, not that it is attributed to
the right field. The cards in the app (built from the DB) are the source of truth.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from decimal import Decimal, InvalidOperation
from typing import Any

PART_RE = re.compile(r"\b76\d[A-Z](?:[0-9A-Z]{2}(?:[-\s]?[0-9A-Z]{4,8}(?:\s*/\s*[A-Z])?)?)?\*?", re.I)
SERIES_RE = re.compile(r"\bseries\s*76\b", re.I)
LIST_MARKER_RE = re.compile(r"^\s*\d+[.)]\s", re.M)
NUMBER_RE = re.compile(r"(?<![\w.])\d{1,3}(?:,\d{3})+(?:\.\d+)?|(?<![\w.])\d+(?:\.\d+)?")
ALLOWLIST = {Decimal(n) for n in ("1", "3", "4", "50", "60", "90")}


def _norm(token: str) -> Decimal | None:
    try:
        return Decimal(token.replace(",", "")).normalize()
    except InvalidOperation:
        return None


def numbers_in_text(text: str) -> list[tuple[str, Decimal]]:
    text = SERIES_RE.sub(" ", text)
    text = PART_RE.sub(" ", text)
    text = LIST_MARKER_RE.sub(" ", text)
    out = []
    for m in NUMBER_RE.finditer(text):
        value = _norm(m.group())
        if value is not None:
            out.append((m.group(), value))
    return out


def numbers_in_data(data: Any) -> set[Decimal]:
    """Numbers inside a JSON structure (values and text)."""
    found: set[Decimal] = set()

    def walk(x: Any) -> None:
        if isinstance(x, bool) or x is None:
            return
        if isinstance(x, (int, float, Decimal)):
            if (v := _norm(str(x))) is not None:
                found.add(v)
        elif isinstance(x, str):
            found.update(v for _, v in numbers_in_text(x))
        elif isinstance(x, dict):
            for v in x.values():
                walk(v)
        elif isinstance(x, Iterable):
            for v in x:
                walk(v)

    walk(data)
    return found


def check(answer: str, sources: list[Any]) -> list[str]:
    """Returns the numbers in `answer` that don't appear in `sources` (empty list = OK)."""
    allowed = numbers_in_data(sources) | ALLOWLIST
    return list(dict.fromkeys(raw for raw, value in numbers_in_text(answer) if value not in allowed))
