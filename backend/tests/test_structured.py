"""Approach B against the real DB (requires `docker compose up -d db` + ingest). Skipped if unavailable."""

import pytest
from sqlalchemy import text

from app.db.session import SessionLocal
from app.retrieval.structured import SearchFilters, lookup_part_number, search_actuators


@pytest.fixture(scope="module")
def session():
    try:
        s = SessionLocal()
        if not s.execute(text("SELECT count(*) FROM actuator")).scalar():
            pytest.skip("empty DB: run pipeline.ingest")
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"DB not available: {exc}")
    yield s
    s.close()


def pns(rows):
    return [r["base_part_number"] for r in rows]


@pytest.mark.parametrize(
    "query, match, first",
    [
        ("761a00-11300000/a", "exact", "761A00-11300000/A"),
        ("765B00 11300000 A", "exact", "765B00-11300000/A"),
        ("763X30-11380000", "prefix", "763X30-11380000/A"),
        ("762B00-11300000/B", "fuzzy", "762B00-11300000/A"),
        ("761A00-11380000/A", "fuzzy", "761A00-11300000/A"),  # summary example: does not exist
        ("ZZZ999", "none", None),
    ],
)
def test_lookup(session, query, match, first):
    r = lookup_part_number(session, query)
    assert r.match_type == match
    assert (pns(r.candidates)[:1] or [None])[0] == first


def test_partial_id_returns_all_variants(session):
    r = lookup_part_number(session, "761A")
    assert r.match_type == "prefix" and len(r.candidates) == 3


def test_right_sizing_in_nm(session):
    rows = search_actuators(session, SearchFilters(phase=3, voltage_v=220, min_torque=1000, torque_unit="nm")).rows
    assert pns(rows) == ["764B00-11330C00/A", "765A00-11330C00/A", "765B00-11330C00/A"]


def test_highest_torque(session):
    rows = search_actuators(session, SearchFilters(sort_by="torque_desc", limit=1)).rows
    assert pns(rows) == ["767C00-11320000/A"]


def test_service_type_uses_its_own_torque(session):
    # 764B00-11320000/A: on/off 9,700 but modulating 7,080 -> does not meet 8,000 modulating.
    rows = search_actuators(
        session, SearchFilters(voltage_v=220, phase=1, enclosure="weatherproof", service_type="modulating", min_torque=8000, limit=10)
    ).rows
    assert "764B00-11320000/A" not in pns(rows) and pns(rows)[0] == "765A00-11320000/A"


def test_na_rows_returned_last_without_numeric_filters(session):
    rows = search_actuators(session, SearchFilters(part_number_prefix="761B", phase=3)).rows
    assert pns(rows) == ["761B00-11330C00/A"] and rows[0]["data_available"] is False


def test_no_match_is_empty(session):
    assert search_actuators(session, SearchFilters(voltage_v=480)).rows == []
    assert search_actuators(session, SearchFilters(min_torque=100_000)).rows == []
