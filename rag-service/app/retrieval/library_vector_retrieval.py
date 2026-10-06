from __future__ import annotations

from dataclasses import dataclass, field
from time import perf_counter
from typing import Any, Protocol

from app.core.config import Settings, get_settings
from app.core.logger import get_logger
from app.indexing import (
    EmbeddingTextBuilder,
    GeminiEmbeddingProvider,
    LibraryVectorSearchScope,
    QdrantVectorStore,
    SearchResult,
    VectorStore,
    build_library_vector_search_query,
)


MAX_LIBRARY_RETRIEVAL_TOP_K = 50

logger = get_logger(__name__)


class QueryEmbeddingProvider(Protocol):
    """Embedding provider capability required by retrieval.

    Chunk ingestion uses document embeddings. Search uses query embeddings, so
    this service only depends on the narrower `embed_query()` behavior instead
    of the full ingestion embedding service.
    """

    async def embed_query(self, query_text: str) -> list[float]:
        raise NotImplementedError


@dataclass(frozen=True)
class LibraryVectorRetrievalRequest:
    """Trusted internal retrieval request after Library authorization.

    Spring Boot should check whether the user can access the requested book or
    ebook first. RAG then converts that scope into mandatory Qdrant filters.
    """

    query: str
    book_id: int | None = None
    ebook_id: int | None = None
    document_id: int | None = None
    top_k: int | None = None
    score_threshold: float | None = None
    expand_context: bool = False
    max_context_chars: int | None = None
    retrieval_mode: str | None = None


@dataclass(frozen=True)
class LibraryVectorRetrievalHit:
    """One chunk returned by vector search, ready for citation/debug display."""

    point_id: str
    score: float
    text: str
    vector_id: str | None = None
    citation: dict[str, Any] = field(default_factory=dict)
    metadata: dict[str, Any] = field(default_factory=dict)
    context_text: str | None = None


@dataclass(frozen=True)
class LibraryVectorRetrievalResponse:
    """Retrieval response before any LLM answer generation happens."""

    query_text_hash: str
    query_text_policy: str
    embedding_version: str
    top_k: int
    applied_filters: dict[str, Any]
    results: list[LibraryVectorRetrievalHit]

    @property
    def result_count(self) -> int:
        return len(self.results)


class LibraryVectorRetrievalService:
    """Baseline Library vector retrieval orchestration.

    This service is intentionally smaller than the full RAG pipeline. It only:

    1. normalizes/builds provider-ready query text;
    2. embeds the query;
    3. builds mandatory Library/Qdrant filters;
    4. returns matching chunks with citation metadata.

    It does not call an LLM and does not generate a final answer yet.
    """

    def __init__(
        self,
        *,
        embedding_provider: QueryEmbeddingProvider | None = None,
        vector_store: VectorStore | None = None,
        text_builder: EmbeddingTextBuilder | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.text_builder = text_builder or EmbeddingTextBuilder(settings=self.settings)
        self.embedding_provider = embedding_provider or GeminiEmbeddingProvider(settings=self.settings)
        self.vector_store = vector_store or QdrantVectorStore(settings=self.settings)

    async def search(self, request: LibraryVectorRetrievalRequest) -> LibraryVectorRetrievalResponse:
        """Run query embedding and filtered Qdrant search for Library ebooks."""

        top_k = self._resolve_top_k(request.top_k)
        scope = LibraryVectorSearchScope(
            book_id=request.book_id,
            ebook_id=request.ebook_id,
            document_id=request.document_id,
            embedding_version=self.settings.embedding_version,
        )
        built_query = self.text_builder.build_query_text(request.query)
        query_vector = await self.embedding_provider.embed_query(built_query.text)
        vector_query = build_library_vector_search_query(
            query_vector=query_vector,
            top_k=top_k,
            scope=scope,
            score_threshold=request.score_threshold,
            settings=self.settings,
        )

        started_at = perf_counter()
        search_results = await self.vector_store.search(vector_query)
        latency_ms = round((perf_counter() - started_at) * 1000, 3)

        logger.info(
            "library_vector_retrieval_completed",
            top_k=top_k,
            result_count=len(search_results),
            latency_ms=latency_ms,
            embedding_version=self.settings.embedding_version,
            score_threshold=request.score_threshold,
            filter_keys=sorted(vector_query.filters.keys()),
        )

        return LibraryVectorRetrievalResponse(
            query_text_hash=built_query.text_hash,
            query_text_policy=built_query.policy,
            embedding_version=self.settings.embedding_version,
            top_k=top_k,
            applied_filters=vector_query.filters,
            results=[_to_library_hit(result) for result in search_results],
        )

    def _resolve_top_k(self, requested_top_k: int | None) -> int:
        top_k = self.settings.retrieval_top_k if requested_top_k is None else requested_top_k
        if isinstance(top_k, bool) or int(top_k) <= 0:
            raise ValueError("top_k must be a positive integer.")
        top_k = int(top_k)
        if top_k > MAX_LIBRARY_RETRIEVAL_TOP_K:
            raise ValueError(f"top_k must be <= {MAX_LIBRARY_RETRIEVAL_TOP_K}.")
        return top_k


def _to_library_hit(result: SearchResult) -> LibraryVectorRetrievalHit:
    metadata = dict(result.metadata or {})
    return LibraryVectorRetrievalHit(
        point_id=result.id,
        vector_id=result.vector_id,
        score=result.score,
        text=result.text,
        citation=_build_citation(metadata),
        metadata=metadata,
    )


def _build_citation(metadata: dict[str, Any]) -> dict[str, Any]:
    """Extract small citation fields from Qdrant payload metadata."""

    citation = {
        "documentId": _first_present(metadata, "documentId", "external_document_id"),
        "documentInternalId": metadata.get("document_id"),
        "bookId": _first_present(metadata, "bookId", "book_id"),
        "ebookId": _first_present(metadata, "ebookId", "ebook_id"),
        "chapterTitle": metadata.get("chapter_title"),
        "chapterIndex": metadata.get("chapter_index"),
        "pageStart": metadata.get("pageStart") or metadata.get("page_start"),
        "pageEnd": metadata.get("pageEnd") or metadata.get("page_end"),
        "chunkIndex": _first_present(metadata, "chunkIndex", "chunk_index"),
        "vectorId": metadata.get("vector_id"),
    }
    return {key: value for key, value in citation.items() if value is not None}


def _first_present(metadata: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = metadata.get(key)
        if value is not None:
            return value
    return None
