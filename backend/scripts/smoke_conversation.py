"""Smoke test of the real agent over HTTP (spends OpenRouter: < USD 0.01 per run).

    uv run uvicorn app.main:app --port 8001 &
    uv run python scripts/smoke_conversation.py [--base-url http://localhost:8001]

Runs a scripted multi-turn conversation (follow-up without repeating the part number + edge cases)
and prints status, cards, tools used and grounding for each turn.
"""

from __future__ import annotations

import argparse
import json
import sys

import httpx

SCRIPT = [
    # (query, expected status, new session?)
    ("What is the output torque of 761A00-11300000/A?", "answered", True),
    ("And how much power does it draw?", "answered", False),
    ("761A00-11380000/A", "not_found", True),
    ("I need an explosionproof actuator, 220V single phase, modulating, at least 1000 in-lbs", "answered", True),
    ("Necesito un actuador de 480V trifásico", "no_match", True),
    ("Give me the data of the 761B in three phase 220V", "data_unavailable", True),
    ("Tell me about the 761A", "clarification_needed", True),
    ("What's the weather like today?", "out_of_scope", True),
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8001")
    ap.add_argument("--email", default="ana@konecto.test")
    ap.add_argument("--password", default="Series76!demo")
    args = ap.parse_args()

    c = httpx.Client(base_url=args.base_url, timeout=120)
    token = c.post("/api/auth/login", json={"email": args.email, "password": args.password}).raise_for_status().json()
    h = {"Authorization": f"Bearer {token['access_token']}"}

    ok = 0
    session_id = None
    total_in = total_out = 0
    for query, expected, new in SCRIPT:
        body = {"query": query} | ({} if new else {"session_id": session_id})
        r = c.post("/api/conversation", json=body, headers=h)
        if r.status_code != 200:
            print(f"\n✗ {query}\n  HTTP {r.status_code}: {r.text}")
            continue
        res = r.json()
        session_id = res["session_id"]
        dbg = c.get(f"/api/messages/{res['message_id']}/debug", headers=h).json()
        total_in += dbg["usage"]["input_tokens"]
        total_out += dbg["usage"]["output_tokens"]
        good = res["status"] == expected
        ok += good
        print(f"\n{'✓' if good else '✗'} [{res['status']}{'' if good else f' ≠ {expected}'}] {query}")
        print(f"  answer: {res['answer'][:300]}")
        if res["products"]:
            print(f"  cards: {[(p['part_number'], p['role']) for p in res['products']]}")
        if res["suggestions"]:
            print(f"  suggestions: {res['suggestions']}")
        for s in dbg["steps"]:
            print(f"  tool: {s['name']}({json.dumps(s['args'], ensure_ascii=False)}) {s['duration_ms']}ms")
        print(f"  grounding: {dbg['grounding']} · {dbg['latency_ms']} ms · tokens {dbg['usage']}")

    print(f"\n{ok}/{len(SCRIPT)} turns with the expected status · tokens in={total_in} out={total_out}")
    sys.exit(0 if ok == len(SCRIPT) else 1)


if __name__ == "__main__":
    main()
