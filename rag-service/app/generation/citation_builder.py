"""Map provider-selected source IDs back to trusted retrieval metadata."""

from __future__ import annotations

from typing import Any

from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalHit


MAX_CITATION_EXCERPT_CHARS = 600


def source_id_for(hit: LibraryVectorRetrievalHit) -> str:
    return hit.vector_id or hit.point_id


def build_citations(
    citation_ids: list[str],
    evidence_by_id: dict[str, LibraryVectorRetrievalHit],
) -> list[dict[str, Any]]:
    """Return only citations that came from the current scoped retrieval call."""

    citations: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source_id in citation_ids:
        if source_id in seen:
            continue
        hit = evidence_by_id.get(source_id)
        if hit is None:
            raise ValueError("LLM cited a source outside the supplied evidence.")
        seen.add(source_id)
        citation = dict(hit.citation)
        citation.update(
            {
                "chunkId": source_id,
                "excerpt": (hit.context_text or hit.text).strip()[:MAX_CITATION_EXCERPT_CHARS],
                "score": hit.score,
            }
        )
        citations.append(citation)
    return citations
