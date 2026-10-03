from __future__ import annotations

from dataclasses import dataclass

from app.core.config import Settings, get_settings
from app.indexing.base import VectorSearchQuery


LIBRARY_EBOOK_SOURCE_TYPE = "LIBRARY_EBOOK"


@dataclass(frozen=True)
class LibraryVectorSearchScope:
    """Trusted Library scope for vector retrieval.

    Spring Boot owns user authorization. RAG receives the already-authorized
    book/ebook/document scope and turns it into mandatory vector filters. This
    prevents A4 search from accidentally searching the whole shared collection.
    """

    book_id: int | None = None
    ebook_id: int | None = None
    document_id: int | None = None
    source_type: str = LIBRARY_EBOOK_SOURCE_TYPE
    embedding_version: str | None = None
    active: bool = True

    def __post_init__(self) -> None:
        if self.book_id is None and self.ebook_id is None and self.document_id is None:
            raise ValueError("LibraryVectorSearchScope requires book_id, ebook_id, or document_id.")
        if not str(self.source_type).strip():
            raise ValueError("LibraryVectorSearchScope.source_type must not be empty.")


def build_library_vector_filters(
    scope: LibraryVectorSearchScope,
    *,
    settings: Settings | None = None,
) -> dict:
    """Build mandatory metadata filters for Library vector search.

    The returned dict is provider-neutral. A later Qdrant-specific layer turns
    these exact filters into Qdrant `Filter` models.
    """

    effective_settings = settings or get_settings()
    filters = {
        "active": bool(scope.active),
        "sourceType": scope.source_type,
        "embedding_version": scope.embedding_version or effective_settings.embedding_version,
    }
    if scope.document_id is not None:
        filters["document_id"] = _positive_int(scope.document_id, "document_id")
    if scope.book_id is not None:
        filters["book_id"] = _positive_int(scope.book_id, "book_id")
    if scope.ebook_id is not None:
        filters["ebook_id"] = _positive_int(scope.ebook_id, "ebook_id")
    return filters


def build_library_vector_search_query(
    *,
    query_vector: list[float],
    top_k: int,
    scope: LibraryVectorSearchScope,
    score_threshold: float | None = None,
    include_vectors: bool = False,
    settings: Settings | None = None,
) -> VectorSearchQuery:
    """Create a VectorSearchQuery with Library filters already enforced."""

    return VectorSearchQuery(
        query_vector=query_vector,
        top_k=top_k,
        filters=build_library_vector_filters(scope, settings=settings),
        score_threshold=score_threshold,
        include_vectors=include_vectors,
    )


def _positive_int(value: int, field_name: str) -> int:
    if isinstance(value, bool) or int(value) <= 0:
        raise ValueError(f"{field_name} must be a positive integer.")
    return int(value)
