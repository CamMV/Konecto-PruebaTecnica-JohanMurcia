"""Compares two extractions (.md) of the PDF cell by cell, after parsing them.

Useful for cross-checking independent extractions (e.g. Gemini vs a manual transcription)
before accepting data/processed/series_76_electric_data.md.

Usage: uv run python -m pipeline.compare_extractions A.md B.md
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from pipeline.parse_markdown import parse


def flatten(md: Path) -> dict[str, dict]:
    catalog = parse(md.read_text())
    out: dict[str, dict] = {}
    for a in catalog.actuators:
        d = a.model_dump(mode="json", exclude={"source"})
        d["ratings"] = {r["service_type"]: r for r in d["ratings"]}
        d["electrical"] = {str(e["frequency_hz"]): e for e in d["electrical"]}
        out[a.base_part_number] = d
    return out


def diff(a, b, path: str = "") -> list[str]:
    if isinstance(a, dict) and isinstance(b, dict):
        return [ln for k in sorted(a.keys() | b.keys()) for ln in diff(a.get(k), b.get(k), f"{path}.{k}" if path else k)]
    return [] if a == b else [f"{path}: {a!r} != {b!r}"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("a", type=Path)
    parser.add_argument("b", type=Path)
    args = parser.parse_args()
    lines = diff(flatten(args.a), flatten(args.b))
    print("\n".join(lines) if lines else "Identical extractions (all values match).")
    sys.exit(1 if lines else 0)


if __name__ == "__main__":
    main()
