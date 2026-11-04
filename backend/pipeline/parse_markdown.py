"""Reviewed Markdown -> Catalog (validated Pydantic models) -> data/processed/actuators.json.

Expected input format (produced by extract_pdf_to_md.py and reviewed by hand):

    <!-- page: 1 -->
    ## WEATHERPROOF - CSA, CE, & UKCA | 110V - Single Phase Power
    | Base Part Number | On/Off Torque (in-lbs) | ... | Motor Power (W) |
    |---|---|...|
    | 761A00-11300000/A | 700 | ... | 15 |
    ### Notes
    - 1. Full load current (FLA) @ Max. rated load

Each table's metadata (enclosure, certifications, voltage, phase, current type) comes from
its heading; the meaning of each column comes from the header (fixed vocabulary in
prompts/extract_tables.md). Nothing is hardcoded per table.

Usage: uv run python -m pipeline.parse_markdown [--md PATH] [--out PATH]
"""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

from app.config import PROCESSED_DIR, REPO_ROOT
from app.schemas.actuator import (
    Actuator,
    Catalog,
    CurrentType,
    ElectricalPoint,
    Enclosure,
    ServiceRating,
    ServiceType,
    SourceRef,
    SourceTable,
)

DEFAULT_MD = PROCESSED_DIR / "series_76_electric_data.md"
DEFAULT_JSON = PROCESSED_DIR / "actuators.json"
SOURCE_PDF = "docs/data/series_76_electric_data.pdf"

PAGE_RE = re.compile(r"<!--\s*page:\s*(\d+)\s*-->")
MISSING = {"N/A", "NA", "-", "—"}

# Column -> (group, field). Group: rating prefix, electrical frequency, or row attribute.
RATING_COLUMNS = {
    "On/Off Torque (in-lbs)": ("on_off", "torque_inlb"),
    "On/Off Torque (Nm)": ("on_off", "torque_nm"),
    "On/Off Duty Cycle (S4%)": ("on_off", "duty_cycle_pct"),
    "Modulating Torque (in-lbs)": ("modulating", "torque_inlb"),
    "Modulating Torque (Nm)": ("modulating", "torque_nm"),
    "Modulating Duty Cycle (S4%)": ("modulating", "duty_cycle_pct"),
    "Torque (in-lbs)": ("general", "torque_inlb"),
    "Torque (Nm)": ("general", "torque_nm"),
    "Duty Cycle (S4%)": ("general", "duty_cycle_pct"),
}
# Cycles/starts per hour belong to On/Off and Modulating respectively; in tables
# without that split (3-phase) they belong to the "general" rating.
RATE_COLUMNS = {"Cycles per Hour": ("on_off", "cycles_per_hour"), "Starts per Hour": ("modulating", "starts_per_hour")}
ELECTRICAL_COLUMNS = {
    f"{name}{suffix}": (freq, attr)
    for freq, suffix_freq in ((60, " 60Hz"), (50, " 50Hz"), (None, ""))
    for name, attr, unit in (("Speed", "operating_speed_s", "(s)"), ("FLA", "fla_a", "(A)"), ("LRA", "lra_a", "(A)"))
    for suffix in [f"{suffix_freq} {unit}"]
}
KNOWN_COLUMNS = {"Base Part Number", "Motor Power (W)", *RATING_COLUMNS, *RATE_COLUMNS, *ELECTRICAL_COLUMNS}


class ParseError(ValueError):
    pass


@dataclass
class RawTable:
    page: int
    heading: str
    line_no: int
    header: list[str] = field(default_factory=list)
    rows: list[tuple[int, list[str]]] = field(default_factory=list)


# ---------------------------------------------------------------- markdown -> raw tables

def split_cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def read_markdown(text: str) -> tuple[list[RawTable], dict[int, list[str]]]:
    tables: list[RawTable] = []
    notes: dict[int, list[str]] = {}
    page = 0
    in_notes = False
    for no, line in enumerate(text.splitlines(), start=1):
        s = line.strip()
        if m := PAGE_RE.search(s):
            page, in_notes = int(m.group(1)), False
        elif s.startswith("## "):
            if page == 0:
                raise ParseError(f"line {no}: table without a preceding '<!-- page: N -->' marker")
            tables.append(RawTable(page=page, heading=s[3:].strip(), line_no=no))
            in_notes = False
        elif s.lower().startswith("### notes"):
            in_notes = True
        elif in_notes and s.startswith(("-", "*")):
            notes.setdefault(page, []).append(s.lstrip("-").strip())
        elif s.startswith("|") and tables and not in_notes:
            cells = split_cells(s)
            if all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                continue  # separator row
            t = tables[-1]
            if not t.header:
                t.header = cells
            else:
                t.rows.append((no, cells))
    return tables, notes


# ---------------------------------------------------------------- heading -> table metadata

def parse_heading(heading: str) -> dict:
    if "|" not in heading:
        raise ParseError(f"heading without '|' separating enclosure and power: {heading!r}")
    enc_part, power = (p.strip() for p in heading.split("|", 1))
    enc_word, _, certs_text = enc_part.partition(" - ")
    try:
        enclosure = Enclosure(enc_word.strip().lower())
    except ValueError as exc:
        raise ParseError(f"unknown enclosure: {enc_word!r}") from exc
    certifications = [c.strip() for c in re.split(r",|&", certs_text) if c.strip()]

    if not (vm := re.search(r"(\d+)\s*V\b", power)):
        raise ParseError(f"voltage not found in {power!r}")
    voltage = int(vm.group(1))
    phase = 1 if re.search(r"single\s+phase", power, re.I) else 3 if re.search(r"\b3\s+phase", power, re.I) else None
    if re.search(r"AC\s*/\s*DC", power, re.I):
        current = CurrentType.AC_DC
    elif re.search(r"\bDC\b", power, re.I):
        current = CurrentType.DC
    else:
        current = CurrentType.AC
    service = "onoff" if re.search(r"on\s*/\s*off", power, re.I) else "mod" if re.search(r"modulating", power, re.I) else None

    supply = f"{phase}ph" if phase else current.value.replace("_", "")
    table_id = "_".join(filter(None, ["wp" if enclosure is Enclosure.WEATHERPROOF else "xp", f"{voltage}v", supply, service]))
    return {
        "table_id": table_id,
        "enclosure": enclosure,
        "certifications": certifications,
        "voltage_v": voltage,
        "phase": phase,
        "current_type": current,
        "power_desc": power,
    }


# ---------------------------------------------------------------- cells

def to_int(cell: str, where: str) -> int:
    try:
        return int(cell.replace(",", ""))
    except ValueError as exc:
        raise ParseError(f"{where}: integer expected, got {cell!r}") from exc


def to_dec(cell: str, where: str) -> Decimal:
    try:
        return Decimal(cell.replace(",", ""))
    except InvalidOperation as exc:
        raise ParseError(f"{where}: decimal expected, got {cell!r}") from exc


def build_actuator(table: RawTable, meta: dict, row_index: int, line_no: int, cells: list[str]) -> Actuator:
    where = f"line {line_no}"
    if len(cells) != len(table.header):
        raise ParseError(f"{where}: {len(cells)} cells but the header has {len(table.header)}")
    row = dict(zip(table.header, cells))
    if any("?" in c for c in cells):
        raise ParseError(f"{where}: unreadable cell ('??') pending manual review")

    pn = row["Base Part Number"].replace("**", "").replace(" ", "")
    not_csa = pn.endswith("*")
    pn = pn.rstrip("*")
    certifications = [c for c in meta["certifications"] if not (not_csa and c == "CSA")]

    values = {k: v for k, v in row.items() if k != "Base Part Number"}
    missing = [k for k, v in values.items() if v.upper() in MISSING]
    common = dict(
        base_part_number=pn,
        enclosure=meta["enclosure"],
        certifications=certifications,
        csa_certified=not not_csa and "CSA" in meta["certifications"],
        voltage_v=meta["voltage_v"],
        phase=meta["phase"],
        current_type=meta["current_type"],
        source=SourceRef(table_id=meta["table_id"], page=table.page, row_index=row_index),
    )
    if missing and len(missing) == len(values):
        return Actuator(**common, data_available=False)
    if missing:
        raise ParseError(f"{where} ({pn}): partial N/A in {missing}; not supported by the schema")

    groups_present = {RATING_COLUMNS[c][0] for c in table.header if c in RATING_COLUMNS}
    ratings: dict[str, dict] = {}
    electrical: dict[int | None, dict] = {}
    for col, cell in values.items():
        if col in RATING_COLUMNS:
            group, attr = RATING_COLUMNS[col]
            ratings.setdefault(group, {})[attr] = to_int(cell, f"{where} {col}")
        elif col in RATE_COLUMNS:
            group, attr = RATE_COLUMNS[col]
            group = group if group in groups_present else "general"
            ratings.setdefault(group, {})[attr] = to_int(cell, f"{where} {col}")
        elif col in ELECTRICAL_COLUMNS:
            freq, attr = ELECTRICAL_COLUMNS[col]
            conv = to_int if attr == "operating_speed_s" else to_dec
            electrical.setdefault(freq, {})[attr] = conv(cell, f"{where} {col}")

    return Actuator(
        **common,
        data_available=True,
        motor_power_w=to_int(row["Motor Power (W)"], f"{where} Motor Power") if "Motor Power (W)" in row else None,
        ratings=[ServiceRating(service_type=ServiceType(g), **r) for g, r in ratings.items()],
        electrical=[ElectricalPoint(frequency_hz=f, **e) for f, e in electrical.items()],
    )


# ---------------------------------------------------------------- entry point

def parse(text: str) -> Catalog:
    raw_tables, notes = read_markdown(text)
    if not raw_tables:
        raise ParseError("no tables found")
    tables: list[SourceTable] = []
    actuators: list[Actuator] = []
    for t in raw_tables:
        unknown = [c for c in t.header if c not in KNOWN_COLUMNS]
        if unknown or "Base Part Number" not in t.header:
            raise ParseError(f"line {t.line_no}: unknown columns {unknown} (see prompts/extract_tables.md)")
        meta = parse_heading(t.heading)
        if any(st.table_id == meta["table_id"] for st in tables):
            raise ParseError(f"line {t.line_no}: duplicated table {meta['table_id']}")
        tables.append(
            SourceTable(
                table_id=meta["table_id"],
                page=t.page,
                title=t.heading,
                enclosure=meta["enclosure"],
                certifications=meta["certifications"],
                power_desc=meta["power_desc"],
                notes=notes.get(t.page, []),
            )
        )
        for i, (line_no, cells) in enumerate(t.rows):
            actuators.append(build_actuator(t, meta, i, line_no, cells))
    return Catalog(source_pdf=SOURCE_PDF, tables=tables, actuators=actuators)


def write_catalog(catalog: Catalog, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(catalog.model_dump_json(indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--md", type=Path, default=DEFAULT_MD)
    parser.add_argument("--out", type=Path, default=DEFAULT_JSON)
    args = parser.parse_args()
    catalog = parse(args.md.read_text())
    write_catalog(catalog, args.out)
    print(f"{len(catalog.tables)} tables, {len(catalog.actuators)} actuators -> {args.out}")


if __name__ == "__main__":
    main()
