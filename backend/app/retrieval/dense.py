"""Dense retriever: cosine similarity in pgvector (exact scan, fewer than 100 documents)."""

from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.orm import Session

from app.retrieval.embeddings import get_embeddings
from app.retrieval.lexical import ScoredDoc

DENSE_SQL = text(
    """
    SELECT id, base_part_number, doc_type, title, 1 - (embedding <=> CAST(:q AS vector)) AS score
    FROM search_document
    WHERE embedding IS NOT NULL
    ORDER BY embedding <=> CAST(:q AS vector), id
    LIMIT :k
    """
)


def search(session: Session, query: str, k: int = 20, query_vector: list[float] | None = None) -> list[ScoredDoc]:
    vec = query_vector if query_vector is not None else get_embeddings().embed_query(query)
    rows = session.execute(DENSE_SQL, {"q": str(vec), "k": k}).all()
    return [ScoredDoc(r.id, r.base_part_number, r.doc_type, r.title, float(r.score)) for r in rows]
