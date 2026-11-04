"""Approach A: hybrid BM25 + dense with Reciprocal Rank Fusion.

RRF(d) = sum over retrievers of 1 / (k + rank_r(d)), k = 60. It only uses ranks, so it does
not need to calibrate BM25 scores (unbounded) against cosine scores ([-1, 1]).

Returns documents (IDs + per-retriever traces for the debug screen). The technical
numbers are NOT taken from here: they are read from `actuator_flat` using `base_part_number`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from sqlalchemy.orm import Session

from app.retrieval import dense, lexical
from app.retrieval.lexical import ScoredDoc

RRF_K = 60
CANDIDATES = 20
Mode = Literal["lexical", "dense", "hybrid"]


@dataclass
class Hit:
    doc_id: int
    base_part_number: str | None
    doc_type: str
    title: str
    score: float
    ranks: dict[str, int] = field(default_factory=dict)  # retriever -> rank (1-based)
    scores: dict[str, float] = field(default_factory=dict)  # retriever -> raw score


def rrf(results: dict[str, list[ScoredDoc]], k: int = RRF_K) -> list[Hit]:
    hits: dict[int, Hit] = {}
    for name, docs in results.items():
        for rank, d in enumerate(docs, start=1):
            h = hits.setdefault(d.doc_id, Hit(d.doc_id, d.base_part_number, d.doc_type, d.title, 0.0))
            h.score += 1 / (k + rank)
            h.ranks[name] = rank
            h.scores[name] = d.score
    return sorted(hits.values(), key=lambda h: (-h.score, h.doc_id))


def search(
    session: Session,
    query: str,
    k: int = 5,
    mode: Mode = "hybrid",
    query_vector: list[float] | None = None,
) -> list[Hit]:
    results: dict[str, list[ScoredDoc]] = {}
    if mode in ("lexical", "hybrid"):
        results["lexical"] = lexical.search(session, query, CANDIDATES)
    if mode in ("dense", "hybrid"):
        results["dense"] = dense.search(session, query, CANDIDATES, query_vector=query_vector)
    return rrf(results)[:k]
