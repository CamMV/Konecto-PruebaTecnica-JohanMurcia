"""PDF (table images) -> Markdown, using a vision model (Gemini, Google AI Studio free tier).

Flow:
  1. pdftoppm renders each page in two resolutions (pass A: 300 dpi, pass B: 200 dpi).
  2. Each render goes to the model with prompts/extract_tables.md (temperature 0).
  3. Self-consistency: the two passes are compared line by line. Differences go to
     data/interim/extraction/diff_report.md for manual review.
  4. Pass A is assembled into data/processed/series_76_electric_data.md.

Responses are cached by hash(image + prompt + model), so re-running costs nothing.

Usage: uv run python -m pipeline.extract_pdf_to_md [--pdf PATH] [--out PATH] [--force]
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import subprocess
import sys
import time
from datetime import date
from pathlib import Path

from app.config import INTERIM_DIR, PROCESSED_DIR, RAW_DIR, get_settings

PROMPT_PATH = Path(__file__).parent / "prompts" / "extract_tables.md"
PASSES = {"A": 300, "B": 200}  # pass -> dpi
WORK_DIR = INTERIM_DIR / "extraction"


def render_pages(pdf: Path, dpi: int, out_dir: Path) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    prefix = out_dir / f"page-{dpi}dpi"
    subprocess.run(["pdftoppm", "-r", str(dpi), "-png", str(pdf), str(prefix)], check=True)
    return sorted(out_dir.glob(f"page-{dpi}dpi-*.png"))


def call_model(image: Path, prompt: str, model: str, api_key: str) -> str:
    from google import genai
    from google.genai import types

    client = genai.Client(api_key=api_key)
    for attempt in range(4):
        try:
            resp = client.models.generate_content(
                model=model,
                contents=[types.Part.from_bytes(data=image.read_bytes(), mime_type="image/png"), prompt],
                config=types.GenerateContentConfig(temperature=0),
            )
            return strip_fences(resp.text or "")
        except Exception as exc:
            code = getattr(exc, "code", None)
            if code is not None and code != 429 and code < 500:
                raise  # permanent error (e.g. 404 model unavailable): don't retry
            wait = 15 * (attempt + 1)  # free tier: rate limits / transient 5xx
            print(f"  ! {type(exc).__name__}: {exc}. Retrying in {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError(f"model call failed for {image.name}")


def strip_fences(text: str) -> str:
    lines = [ln for ln in text.strip().splitlines() if not ln.strip().startswith("```")]
    return "\n".join(lines).strip() + "\n"


def extract_page(image: Path, prompt: str, model: str, api_key: str | None, force: bool) -> str:
    key = hashlib.sha256(image.read_bytes() + prompt.encode() + model.encode()).hexdigest()[:16]
    cache = WORK_DIR / "cache" / f"{image.stem}.{key}.md"
    if cache.exists() and not force:
        print(f"  = {image.name} (cache)")
        return cache.read_text()
    if not api_key:
        sys.exit("GOOGLE_API_KEY is not set (.env). Required to extract the PDF.")
    print(f"  > {image.name} -> {model}")
    text = call_model(image, prompt, model, api_key)
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(text)
    return text


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pdf", type=Path, default=RAW_DIR / "series_76_electric_data.pdf")
    parser.add_argument("--out", type=Path, default=PROCESSED_DIR / "series_76_electric_data.md")
    parser.add_argument("--force", action="store_true", help="ignore the cache")
    args = parser.parse_args()

    settings = get_settings()
    api_key = settings.google_api_key if settings.google_api_key and "your-" not in settings.google_api_key else None
    prompt = PROMPT_PATH.read_text()

    pages: dict[str, list[str]] = {}
    for name, dpi in PASSES.items():
        print(f"Pass {name} ({dpi} dpi)")
        images = render_pages(args.pdf, dpi, WORK_DIR / "pages")
        pages[name] = [extract_page(img, prompt, settings.extraction_model, api_key, args.force) for img in images]

    # Self-consistency between passes.
    report = ["# Diff between extraction passes (A=300dpi vs B=200dpi)\n"]
    n_diff = 0
    for i, (a, b) in enumerate(zip(pages["A"], pages["B"]), start=1):
        diff = list(difflib.unified_diff(a.splitlines(), b.splitlines(), "A", "B", lineterm="", n=0))
        if diff:
            n_diff += sum(1 for ln in diff if ln[:1] in "+-" and ln[:3] not in ("+++", "---"))
            report += [f"## Page {i}\n", "```diff", *diff, "```\n"]
    (WORK_DIR / "diff_report.md").write_text("\n".join(report))

    header = [
        "# Series 76: Electrical Data",
        "",
        f"> Source: `docs/data/series_76_electric_data.pdf`. Extracted with `{settings.extraction_model}` "
        f"on {date.today().isoformat()} using `backend/pipeline/extract_pdf_to_md.py` and **reviewed by hand**.",
        "> This file is the input for ingestion (`pipeline.ingest --from-md`).",
        "",
    ]
    body = [f"<!-- page: {i} -->\n\n{text}" for i, text in enumerate(pages["A"], start=1)]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(header) + "\n".join(body))

    print(f"\nWrote {args.out}")
    if n_diff:
        print(f"WARNING: {n_diff} lines differ between passes -> review {WORK_DIR / 'diff_report.md'}")
    else:
        print("Both passes match.")


if __name__ == "__main__":
    main()
