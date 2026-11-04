"""Lexical retriever: Okapi BM25 computed in SQL over `search_document.tsv`.

- tf: number of positions of the lexeme in the document's tsvector.
- df / N / avgdl: computed over the collection at query time (fewer than 100 documents).
- The GIN index on `tsv` pre-filters candidates with `tsv @@ (t1 | t2 | ...)`.

Scaling note: recomputing df/avgdl on every query is O(collection). For thousands of
documents, swap in ParadeDB `pg_search` (native BM25 in Postgres) behind the same interface.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import text
from sqlalchemy.orm import Session

from app.retrieval.text import lexical_tokens, unique

K1 = 1.2
B = 0.75

BM25_SQL = text(
    """
    WITH docs AS (
        SELECT id, tsv FROM search_document
    ),
    doclen AS (
        SELECT d.id, sum(array_length(u.positions, 1)) AS len
        FROM docs d, unnest(d.tsv) u GROUP BY d.id
    ),
    stats AS (
        SELECT count(*)::float AS n, avg(len)::float AS avgdl FROM doclen
    ),
    df AS (
        SELECT u.lexeme, count(*)::float AS df
        FROM docs d, unnest(d.tsv) u
        WHERE u.lexeme = ANY(:terms) GROUP BY u.lexeme
    ),
    tf AS (
        SELECT d.id, u.lexeme, array_length(u.positions, 1)::float AS tf
        FROM search_document d, unnest(d.tsv) u
        WHERE d.tsv @@ to_tsquery('simple', :tsquery) AND u.lexeme = ANY(:terms)
    )
    SELECT sd.id, sd.base_part_number, sd.doc_type, sd.title,
           sum(ln(1 + (s.n - df.df + 0.5) / (df.df + 0.5))
               * tf.tf * (:k1 + 1) / (tf.tf + :k1 * (1 - :b + :b * dl.len / s.avgdl))) AS score
    FROM tf
    JOIN df USING (lexeme)
    JOIN doclen dl USING (id)
    JOIN search_document sd ON sd.id = tf.id
    CROSS JOIN stats s
    GROUP BY sd.id, sd.base_part_number, sd.doc_type, sd.title
    ORDER BY score DESC, sd.id
    LIMIT :k
    """
)


@dataclass(frozen=True)
class ScoredDoc:
    doc_id: int
    base_part_number: str | None
    doc_type: str
    title: str
    score: float


def search(session: Session, query: str, k: int = 20) -> list[ScoredDoc]:
    terms = unique(lexical_tokens(query))
    if not terms:
        return []
    rows = session.execute(
        BM25_SQL, {"terms": terms, "tsquery": " | ".join(terms), "k1": K1, "b": B, "k": k}
    ).all()
    return [ScoredDoc(r.id, r.base_part_number, r.doc_type, r.title, float(r.score)) for r in rows]
