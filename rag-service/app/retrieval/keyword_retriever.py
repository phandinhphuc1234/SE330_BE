"""Scoped BM25 retrieval over chunks persisted in PostgreSQL."""

from __future__ import annotations

from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
import math
import re
from typing import Any, Protocol

from sqlalchemy import func, literal_column, select

from app.core.config import Settings, get_settings
from app.db.session import async_session_factory
from app.documents.models import Document, DocumentChunk
from app.indexing import LibraryVectorSearchScope, SearchResult
from app.indexing.qdrant_store import deterministic_qdrant_point_id


_TOKEN_PATTERN = re.compile(r"[^\W_]+(?:['’][^\W_]+)?", re.UNICODE)


@dataclass(frozen=True)
class KeywordCandidate:
    chunk_id: int
    vector_id: str | None
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)


class KeywordCandidateSource(Protocol):
    async def load(
        self,
        scope: LibraryVectorSearchScope,
        *,
        limit: int,
        query: str,
    ) -> list[KeywordCandidate]:
        raise NotImplementedError


class PostgresKeywordCandidateSource:
    """Load only chunks inside the already-authorized Library scope."""

    def __init__(self, *, session_factory: Callable = async_session_factory) -> None:
        self.session_factory = session_factory

    async def load(
        self,
        scope: LibraryVectorSearchScope,
        *,
        limit: int,
        query: str,
    ) -> list[KeywordCandidate]:
        statement = (
            select(DocumentChunk, Document)
            .join(Document, Document.id == DocumentChunk.document_id)
            .where(Document.source_type == scope.source_type)
            .where(Document.metadata_["ingestion_status"].as_string() == "INDEXED")
            .where(Document.metadata_["embedding_version"].as_string() == scope.embedding_version)
            .where(func.coalesce(DocumentChunk.metadata_["active"].as_boolean(), True) == scope.active)
            .order_by(DocumentChunk.document_id, DocumentChunk.chunk_index)
        )
        if scope.document_id is not None:
            statement = statement.where(Document.id == scope.document_id)
        if scope.book_id is not None:
            statement = statement.where(Document.book_id == scope.book_id)
        if scope.ebook_id is not None:
            statement = statement.where(Document.ebook_id == scope.ebook_id)

        async with self.session_factory() as session:
            rows = (await session.execute(statement.limit(limit + 1))).all()
            if len(rows) > limit:
                # A single ebook normally has <=1000 chunks. For a larger book
                # scope, use the GIN index to bound candidates before BM25.
                # The index expression must match the migration exactly.
                tokens = re.findall(r"[^\W_]+", query.casefold(), re.UNICODE)[:64]
                if not tokens:
                    return []
                config = literal_column("'simple'::regconfig")
                search_vector = func.to_tsvector(config, DocumentChunk.content)
                tsquery = func.to_tsquery(config, " | ".join(dict.fromkeys(tokens)))
                narrowed = statement.order_by(None).where(search_vector.op("@@")(tsquery))
                narrowed = narrowed.order_by(func.ts_rank_cd(search_vector, tsquery).desc(), DocumentChunk.id).limit(limit)
                rows = (await session.execute(narrowed)).all()

        candidates: list[KeywordCandidate] = []
        for chunk, document in rows:
            metadata = dict(chunk.metadata_ or {})
            metadata.update(
                {
                    "document_id": document.id,
                    "documentId": document.external_document_id,
                    "external_document_id": document.external_document_id,
                    "sourceType": document.source_type,
                    "source_type": document.source_type,
                    "book_id": document.book_id,
                    "bookId": document.book_id,
                    "ebook_id": document.ebook_id,
                    "ebookId": document.ebook_id,
                    "chunk_index": chunk.chunk_index,
                    "chunkIndex": chunk.chunk_index,
                    "vector_id": chunk.vector_id,
                    "embedding_version": document.metadata_.get("embedding_version"),
                }
            )
            candidates.append(
                KeywordCandidate(
                    chunk_id=chunk.id,
                    vector_id=chunk.vector_id,
                    text=chunk.content,
                    metadata={key: value for key, value in metadata.items() if value is not None},
                )
            )
        return candidates


class KeywordRetriever:
    """Rank scoped PostgreSQL chunks with the Okapi BM25 formula."""

    def __init__(
        self,
        *,
        candidate_source: KeywordCandidateSource | None = None,
        settings: Settings | None = None,
        k1: float = 1.5,
        b: float = 0.75,
    ) -> None:
        self.settings = settings or get_settings()
        self.candidate_source = candidate_source or PostgresKeywordCandidateSource()
        self.k1 = k1
        self.b = b

    async def search(
        self,
        query: str,
        top_k: int,
        *,
        scope: LibraryVectorSearchScope,
    ) -> list[SearchResult]:
        if top_k <= 0:
            raise ValueError("top_k must be positive.")
        query_tokens = tokenize(query)
        if not query_tokens:
            return []

        candidates = await self.candidate_source.load(
            scope,
            limit=self.settings.keyword_candidate_limit,
            query=query,
        )
        scored = bm25_score_candidates(
            query_tokens,
            candidates,
            k1=self.k1,
            b=self.b,
        )
        if not scored:
            return []

        maximum = scored[0][0]
        results: list[SearchResult] = []
        for score, candidate in scored[:top_k]:
            normalized_score = score / maximum if maximum > 0 else 0.0
            vector_id = candidate.vector_id or f"chunk-{candidate.chunk_id}"
            embedding_version = str(
                candidate.metadata.get("embedding_version") or self.settings.embedding_version
            )
            point_id = deterministic_qdrant_point_id(f"{vector_id}:{embedding_version}")
            metadata = dict(candidate.metadata)
            metadata["bm25_score"] = score
            results.append(
                SearchResult(
                    id=point_id,
                    vector_id=vector_id,
                    score=normalized_score,
                    text=candidate.text,
                    metadata=metadata,
                    retrieval_source="keyword",
                )
            )
        return results


def tokenize(text: str) -> list[str]:
    return [token.casefold() for token in _TOKEN_PATTERN.findall(str(text or ""))]


def bm25_score_candidates(
    query_tokens: list[str],
    candidates: list[KeywordCandidate],
    *,
    k1: float = 1.5,
    b: float = 0.75,
) -> list[tuple[float, KeywordCandidate]]:
    """Return positive-score candidates in stable descending BM25 order."""

    if not query_tokens or not candidates:
        return []
    documents = [tokenize(candidate.text) for candidate in candidates]
    average_length = sum(len(tokens) for tokens in documents) / len(documents)
    if average_length <= 0:
        return []

    unique_query_tokens = set(query_tokens)
    document_sets = [set(document) for document in documents]
    document_frequency = {
        token: sum(1 for document in document_sets if token in document)
        for token in unique_query_tokens
    }
    corpus_size = len(documents)
    query_frequency = Counter(query_tokens)
    ranked: list[tuple[float, int, KeywordCandidate]] = []

    for order, (candidate, document_tokens) in enumerate(zip(candidates, documents, strict=True)):
        term_frequency = Counter(document_tokens)
        document_length = len(document_tokens)
        score = 0.0
        for token, query_count in query_frequency.items():
            frequency = term_frequency[token]
            if frequency == 0:
                continue
            df = document_frequency[token]
            inverse_document_frequency = math.log(
                1.0 + (corpus_size - df + 0.5) / (df + 0.5)
            )
            denominator = frequency + k1 * (
                1.0 - b + b * document_length / average_length
            )
            score += query_count * inverse_document_frequency * (
                frequency * (k1 + 1.0) / denominator
            )
        if score > 0:
            ranked.append((score, order, candidate))

    ranked.sort(key=lambda item: (-item[0], item[1]))
    return [(score, candidate) for score, _, candidate in ranked]
