from decimal import Decimal
from pathlib import Path

import pytest

from app.schemas.actuator import CurrentType, Enclosure, ServiceType, normalize_part_number
from pipeline.parse_markdown import ParseError, parse, parse_heading
from pipeline.validate import validate

MINI = (Path(__file__).parent / "fixtures" / "mini.md").read_text()
EXPECTED = {"wp_220v_1ph": 2, "wp_220v_3ph": 2, "xp_24v_dc_mod": 1}


@pytest.fixture(scope="module")
def catalog():
    return parse(MINI)


def by_pn(catalog, pn):
    return next(a for a in catalog.actuators if a.base_part_number == pn)


def test_tables_and_rows(catalog):
    assert [t.table_id for t in catalog.tables] == list(EXPECTED)
    assert len(catalog.actuators) == 5


def test_split_table_has_on_off_and_modulating(catalog):
    a = by_pn(catalog, "765B00-11320000/A")
    ratings = {r.service_type: r for r in a.ratings}
    assert ratings[ServiceType.ON_OFF].torque_inlb == 26500
    assert ratings[ServiceType.ON_OFF].cycles_per_hour == 3
    assert ratings[ServiceType.MODULATING].starts_per_hour == 840
    elec = {e.frequency_hz: e for e in a.electrical}
    assert elec[60].fla_a == Decimal("3.5") and elec[50].operating_speed_s == 130
    assert a.motor_power_w == 180 and a.csa_certified


def test_asterisk_means_not_csa(catalog):
    a = by_pn(catalog, "766A00-11320000/A")
    assert not a.csa_certified
    assert a.certifications == ["CE", "UKCA"]
    assert a.size == 6 and a.model_code == "766A"


def test_na_row_has_no_data(catalog):
    a = by_pn(catalog, "761A00-11330C00/A")
    assert not a.data_available and a.ratings == [] and a.motor_power_w is None


def test_three_phase_is_general_rating(catalog):
    a = by_pn(catalog, "763A00-11330C00/A")
    (r,) = a.ratings
    assert r.service_type is ServiceType.GENERAL
    assert r.cycles_per_hour == 39 and r.starts_per_hour == 1200
    assert a.phase == 3


def test_24v_has_no_frequency(catalog):
    a = by_pn(catalog, "761X30-11370000/A")
    assert a.current_type is CurrentType.DC and a.phase is None
    assert [e.frequency_hz for e in a.electrical] == [None]
    assert a.enclosure is Enclosure.EXPLOSIONPROOF


def test_notes_attached_to_page(catalog):
    assert any("not CSA" in n for n in catalog.tables[0].notes)


def test_validate_clean(catalog):
    assert [i for i in validate(catalog, EXPECTED) if i.level == "ERROR"] == []


def test_validate_detects_bad_nm_and_counts():
    bad = parse(MINI.replace("| 560 | 65 |", "| 560 | 165 |"))
    errors = [str(i) for i in validate(bad, EXPECTED | {"xp_24v_dc_mod": 2}) if i.level == "ERROR"]
    assert any("165 Nm" in e for e in errors)
    assert any("expected 2 rows" in e for e in errors)


def test_unreadable_cell_rejected():
    with pytest.raises(ParseError, match="unreadable"):
        parse(MINI.replace("| 560 |", "| 5?0 |"))


def test_lra_below_fla_rejected():
    with pytest.raises(ValueError, match="LRA"):
        parse(MINI.replace("| 5.0 | 12.8 |", "| 5.0 | 1.8 |"))


def test_heading_parsing():
    m = parse_heading("EXPLOSIONPROOF - CSA | 24V AC/DC Power - On/Off")
    assert m["table_id"] == "xp_24v_acdc_onoff" and m["current_type"] is CurrentType.AC_DC


def test_normalize_part_number():
    assert normalize_part_number(" 761a00-11300000/a ") == "761A0011300000A"


def test_lexical_tokens():
    from app.retrieval.text import lexical_tokens

    assert lexical_tokens("761A00-11300000/A") == ["761a0011300000a", "761a00", "11300000", "761a"]
    assert lexical_tokens("FLA 11.2 A, torque 1,330 in-lbs") == ["fla", "11.2", "torque", "1330", "inlbs", "lbs"]
