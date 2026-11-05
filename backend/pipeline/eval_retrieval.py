"""Evaluates retrieval against eval/retrieval_cases.yaml:
approach A (lexical / dense / hybrid) vs approach B (structured).

Metrics per mode, over cases that have a correct answer:
  - hit@1, hit@5: is a correct part number in top-1 / top-5?
  - MRR: 1 / rank of the first correct part number (0 if not in the top-10).
  - recall@5: fraction of the valid set present in the top-5 (relevant when there are several).
Over `no_answer` cases:
  - abstention: does the mode recognize that there is no answer? (A always returns something;
    B abstains when lookup gives fuzzy/none or the search comes back empty).

Mode `structured` (approach B): the LLM (AGENT_MODEL, via OpenRouter) receives the query and
the tools from app/agent/tools.py and picks ONE tool with its arguments (tool calling,
like the agent will). Tool calls are cached in data/interim/eval_cache/ so re-runs cost nothing.
In `fuzzy` lookups the suggestions count for hit@k ("did you mean…?" is correct for a typo),
but the case counts as an abstention (the part number does not exist).

Usage:
    uv run python -m pipeline.eval_retrieval [--modes lexical,dense,hybrid,structured] [--out docs/retrieval_eval.md]
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import yaml
from sqlalchemy import text

from app.config import INTERIM_DIR, REPO_ROOT, get_settings
from app.db.session import SessionLocal
from app.retrieval import structured
from app.retrieval.hybrid import search

CASES = Path(__file__).parent / "eval" / "retrieval_cases.yaml"
CACHE = INTERIM_DIR / "eval_cache" / "tool_calls.json"
TOP_K = 10
ROUTER_SYSTEM = (
    "You are the retrieval step of a technical assistant for Series 76 electric actuators. "
    "Call exactly ONE tool to fetch the data needed to answer the user's message. "
    "Do not answer directly. Users may write in English or Spanish."
)


@dataclass
class Outcome:
    ranked: list[str]
    abstained: bool
    trace: str = ""


def expected_set(session, case: dict) -> list[str]:
    if "expected_where" in case:
        sql = f"SELECT base_part_number FROM actuator_flat WHERE {case['expected_where']} ORDER BY 1"
        return [r[0] for r in session.execute(text(sql))]
    return case.get("expected", [])


# ---------------------------------------------------------------- approach A

def run_hybrid(session, query: str, mode: str, vector) -> Outcome:
    hits = search(session, query, k=TOP_K * 3, mode=mode, query_vector=vector)
    seen: list[str] = []
    for h in hits:
        if h.base_part_number and h.base_part_number not in seen:
            seen.append(h.base_part_number)
    return Outcome(seen[:TOP_K], abstained=not seen)


# ---------------------------------------------------------------- approach B

def route_queries(queries: list[str]) -> dict[str, dict]:
    """query -> {"name": tool, "args": {...}} using tool calling; with a disk cache."""
    from langchain_core.messages import HumanMessage, SystemMessage
    from langchain_core.utils.function_calling import convert_to_openai_tool

    from app.llm import get_chat_model
    from app.agent.tools import TOOLS

    settings = get_settings()
    schema = json.dumps([convert_to_openai_tool(t) for t in TOOLS], sort_keys=True)
    salt = settings.agent_model + ROUTER_SYSTEM + schema
    cache: dict[str, dict] = json.loads(CACHE.read_text()) if CACHE.exists() else {}
    out: dict[str, dict] = {}
    llm = None
    for q in queries:
        key = hashlib.sha256((salt + q).encode()).hexdigest()[:20]
        if key not in cache:
            llm = llm or get_chat_model().bind_tools(TOOLS, tool_choice="any")
            msg = llm.invoke([SystemMessage(ROUTER_SYSTEM), HumanMessage(q)])
            call = msg.tool_calls[0] if msg.tool_calls else {"name": "none", "args": {}}
            cache[key] = {"query": q, "name": call["name"], "args": call["args"], "model": settings.agent_model}
            CACHE.parent.mkdir(parents=True, exist_ok=True)
            CACHE.write_text(json.dumps(cache, indent=2, ensure_ascii=False))
        out[q] = cache[key]
    return out


def run_structured(session, call: dict) -> Outcome:
    name, args = call["name"], call["args"]
    trace = f"`{name}({json.dumps(args, ensure_ascii=False)})`"
    if name == "lookup_actuator":
        r = structured.lookup_part_number(session, args.get("part_number", ""))
        return Outcome([c["base_part_number"] for c in r.candidates][:TOP_K], not r.found, f"{trace} → {r.match_type}")
    if name == "search_actuators":
        f = structured.SearchFilters(**args).model_copy(update={"limit": TOP_K})
        rows = structured.search_actuators(session, f).rows
        return Outcome([r["base_part_number"] for r in rows], not rows, f"{trace} → {len(rows)} results")
    return Outcome([], True, f"{trace} (no tool)")


# ---------------------------------------------------------------- metrics & report

def metrics(ranked: list[str], expected: list[str]) -> dict[str, float]:
    exp = set(expected)
    first = next((i for i, p in enumerate(ranked, start=1) if p in exp), None)
    return {
        "hit@1": float(bool(ranked[:1] and ranked[0] in exp)),
        "hit@5": float(any(p in exp for p in ranked[:5])),
        "mrr": 1 / first if first else 0.0,
        "recall@5": len(exp & set(ranked[:5])) / min(len(exp), 5),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--modes", default="lexical,dense,hybrid,structured")
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "docs" / "retrieval_eval.md")
    args = parser.parse_args()
    modes = args.modes.split(",")
    cases = yaml.safe_load(CASES.read_text())
    settings = get_settings()

    vectors: dict[str, list[float] | None] = {c["id"]: None for c in cases}
    if any(m in modes for m in ("dense", "hybrid")):
        from app.retrieval.embeddings import get_embeddings

        embs = get_embeddings().embed_documents([c["query"] for c in cases])  # one batched call
        vectors = {c["id"]: v for c, v in zip(cases, embs)}
    calls = route_queries([c["query"] for c in cases]) if "structured" in modes else {}

    per_mode: dict[str, dict[str, list[float]]] = {m: {} for m in modes}
    per_cat: dict[str, dict[str, list[float]]] = {m: {} for m in modes}
    abstain: dict[str, list[float]] = {m: [] for m in modes}
    detail: list[str] = []
    no_answer: list[str] = []
    traces: list[str] = []

    with SessionLocal() as session:
        for case in cases:
            exp = expected_set(session, case)
            row = [case["id"], case["category"]]
            for m in modes:
                o = (
                    run_structured(session, calls[case["query"]])
                    if m == "structured"
                    else run_hybrid(session, case["query"], m, vectors[case["id"]])
                )
                if m == "structured":
                    traces.append(f"| {case['id']} | {case['query']} | {o.trace} |")
                if not exp:
                    abstain[m].append(float(o.abstained))
                    verdict = "✅ abstains" if o.abstained else "❌ answers"
                    no_answer.append(f"| {case['id']} | {m} | {verdict} | {', '.join(o.ranked[:3]) or '-'} |")
                    continue
                res = metrics(o.ranked, exp)
                for k, v in res.items():
                    per_mode[m].setdefault(k, []).append(v)
                per_cat[m].setdefault(case["category"], []).append(res["hit@1"])
                row.append(f"{'✅' if res['hit@1'] else '🟡' if res['hit@5'] else '❌'} {o.ranked[0] if o.ranked else '-'}")
            if exp:
                detail.append(f"| {' | '.join(row)} | {len(exp)} |")

    avg = lambda xs: sum(xs) / len(xs) if xs else 0.0  # noqa: E731
    keys = ["hit@1", "hit@5", "mrr", "recall@5"]
    label = {"lexical": "A · lexical (BM25)", "dense": "A · dense", "hybrid": "A · hybrid (RRF)", "structured": "B · structured"}
    summary = [
        "| mode | " + " | ".join(keys) + " | abstention |",
        "|---|" + "---|" * (len(keys) + 1),
        *[
            f"| {label.get(m, m)} | " + " | ".join(f"{avg(per_mode[m].get(k, [])):.2f}" for k in keys)
            + f" | {avg(abstain[m]):.2f} |"
            for m in modes
        ],
    ]
    n_ans = sum(1 for c in cases if c["category"] != "no_answer")
    out = [
        "# Retrieval evaluation: approach A (hybrid BM25 + dense) vs approach B (structured)",
        "",
        f"Generated by `backend/pipeline/eval_retrieval.py` on {date.today().isoformat()}. "
        f"Embeddings: `{settings.embedding_model}`. Agent/router (B): `{settings.agent_model}`. "
        f"Cases: `backend/pipeline/eval/retrieval_cases.yaml` ({n_ans} with an answer, {len(cases) - n_ans} with no answer).",
        "",
        "## Summary",
        "",
        *summary,
        "",
        "`abstention` = fraction of the no-answer cases where the mode recognizes there is no answer.",
        "",
        "## hit@1 by category",
        "",
        "| category | " + " | ".join(label.get(m, m) for m in modes) + " |",
        "|---|" + "---|" * len(modes),
    ]
    cats = list(dict.fromkeys(c["category"] for c in cases if c["category"] != "no_answer"))
    out += [f"| {c} | " + " | ".join(f"{avg(per_cat[m].get(c, [])):.2f}" for m in modes) + " |" for c in cats]
    out += [
        "",
        "## Detail (top-1 per mode; ✅ correct top-1, 🟡 correct in top-5, ❌ not in top-5)",
        "",
        "| case | category | " + " | ".join(modes) + " | # valid |",
        "|---|---|" + "---|" * len(modes) + "---|",
        *detail,
        "",
        "## Cases with no answer in the catalog",
        "",
        "| case | mode | result | top-3 |",
        "|---|---|---|---|",
        *no_answer,
    ]
    if traces:
        out += ["", "## Approach B: tool chosen by the LLM", "", "| case | query | tool call → result |", "|---|---|---|", *traces]
    args.out.write_text("\n".join(out) + "\n")
    print("\n".join(summary))
    print(f"\nReport: {args.out}")


if __name__ == "__main__":
    main()
