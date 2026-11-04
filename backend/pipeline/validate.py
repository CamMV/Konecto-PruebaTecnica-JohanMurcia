"""Consistency checks over the catalog, to catch extraction errors (OCR/vision).

- ERROR (blocks ingestion): table/row counts different from the manifest, duplicated
  part numbers, in-lbs <-> Nm conversion out of tolerance (a misread digit).
- WARNING (review by hand): physical heuristics that the source data *could* violate
  (50 Hz speed < 60 Hz speed, unusual motor power, torque not increasing within a table).

The per-row checks (part-number format, LRA >= FLA, duty 1-100, N/A without data) are already
enforced by the Pydantic models in app/schemas/actuator.py.

Usage: uv run python -m pipeline.validate [--json PATH]
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

from app.schemas.actuator import Catalog, ServiceType
from pipeline.parse_markdown import DEFAULT_JSON

INLB_TO_NM = 0.112985
NM_TOLERANCE = 0.08

# Expected rows per table in series_76_electric_data.pdf (manual count against the PDF).
EXPECTED_ROWS: dict[str, int] = {
    "wp_110v_1ph": 11,
    "wp_220v_1ph": 16,
    "wp_220v_3ph": 11,
    "xp_24v_acdc_onoff": 5,
    "xp_24v_dc_mod": 5,
    "xp_110v_1ph": 10,
    "xp_220v_1ph": 10,
}
TYPICAL_MOTOR_POWER_W = {15, 40, 90, 180, 700}


@dataclass
class Issue:
    level: str  # "ERROR" | "WARNING"
    where: str
    message: str

    def __str__(self) -> str:
        return f"[{self.level}] {self.where}: {self.message}"


def validate(catalog: Catalog, expected_rows: dict[str, int] | None = EXPECTED_ROWS) -> list[Issue]:
    issues: list[Issue] = []
    err = lambda w, m: issues.append(Issue("ERROR", w, m))  # noqa: E731
    warn = lambda w, m: issues.append(Issue("WARNING", w, m))  # noqa: E731

    # --- counts per table
    counts: dict[str, int] = {}
    for a in catalog.actuators:
        counts[a.source.table_id] = counts.get(a.source.table_id, 0) + 1
    if expected_rows is not None:
        for tid, n in expected_rows.items():
            if counts.get(tid, 0) != n:
                err(tid, f"expected {n} rows, found {counts.get(tid, 0)}")
        for tid in counts.keys() - expected_rows.keys():
            err(tid, "table not present in the manifest")

    # --- unique identifiers
    seen: dict[str, str] = {}
    for a in catalog.actuators:
        norm = a.part_number_normalized
        if norm in seen:
            err(a.base_part_number, f"duplicated (also in {seen[norm]})")
        seen[norm] = a.source.table_id

    # --- per-row checks
    prev_torque: dict[str, tuple[str, int]] = {}
    for a in catalog.actuators:
        w = f"{a.base_part_number} [{a.source.table_id}#{a.source.row_index}]"
        for r in a.ratings:
            expected_nm = r.torque_inlb * INLB_TO_NM
            if abs(r.torque_nm - expected_nm) / expected_nm > NM_TOLERANCE:
                err(w, f"{r.service_type}: {r.torque_inlb} in-lbs ≈ {expected_nm:.0f} Nm but the table says {r.torque_nm} Nm")
        by_freq = {e.frequency_hz: e for e in a.electrical}
        if 60 in by_freq and 50 in by_freq and by_freq[50].operating_speed_s < by_freq[60].operating_speed_s:
            warn(w, "50 Hz speed is faster than 60 Hz speed")
        if a.motor_power_w is not None and a.motor_power_w not in TYPICAL_MOTOR_POWER_W:
            warn(w, f"unusual motor power: {a.motor_power_w} W")
        main = next((r for r in a.ratings if r.service_type in (ServiceType.ON_OFF, ServiceType.GENERAL)), None)
        if main:
            tid = a.source.table_id
            if tid in prev_torque and main.torque_inlb < prev_torque[tid][1]:
                warn(w, f"torque {main.torque_inlb} is lower than the previous row ({prev_torque[tid][0]}: {prev_torque[tid][1]})")
            prev_torque[tid] = (a.base_part_number, main.torque_inlb)
    return issues


def report(issues: list[Issue]) -> bool:
    """Prints the issues. Returns True if there are no errors."""
    for i in issues:
        print(i)
    n_err = sum(i.level == "ERROR" for i in issues)
    n_warn = len(issues) - n_err
    print(f"Validation: {n_err} errors, {n_warn} warnings")
    return n_err == 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    args = parser.parse_args()
    catalog = Catalog.model_validate_json(args.json.read_text())
    sys.exit(0 if report(validate(catalog)) else 1)


if __name__ == "__main__":
    main()
