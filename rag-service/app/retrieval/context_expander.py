"""Neighbour expansion and deterministic context-budget compression."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, replace
import hashlib
import re
from typing import Protocol

from sqlalchemy import func, or_, select

from app.core.config import get_settings
from app.db.session import async_session_factory
from app.documents.models import Document, DocumentChunk
from app.indexing import SearchResult
from app.indexing.qdrant_store import deterministic_qdrant_point_id
from app.retrieval.keyword_retriever import tokenize
from app.retrieval.context_scope import (
    REVISION_FIELDS, chapter_relation, chunk_index as resolve_chunk_index,
    expansion_scope, non_negative_int, page_interval, positive_int, read_alias, text_value,
)


@dataclass(frozen=True)
class NeighborChunk:
    document_id: int
    chunk_index: int
    text: str
    vector_id: str | None
    metadata: dict


class NeighborChunkSource(Protocol):
    async def load(
        self,
        ranges: dict[int, list[tuple[int, int]]],
    ) -> list[NeighborChunk]:
        raise NotImplementedError


class PostgresNeighborChunkSource:
    def __init__(self, *, session_factory: Callable = async_session_factory, settings=None) -> None:
        self.session_factory = session_factory
        self.settings = settings or get_settings()

    async def load(
        self,
        ranges: dict[int, list[tuple[int, int]]],
    ) -> list[NeighborChunk]:
        if not ranges:
            return []
        for document_id, intervals in ranges.items():
            if type(document_id) is not int or document_id <= 0:
                raise ValueError("Neighbor ranges require positive integer document IDs.")
            for interval in intervals:
                if (len(interval) != 2 or any(type(value) is not int for value in interval)
                        or interval[0] < 0 or interval[1] < interval[0]):
                    raise ValueError("Neighbor ranges require ordered non-negative integer bounds.")
        clauses = [
            (
                (DocumentChunk.document_id == document_id)
                & (DocumentChunk.chunk_index >= start)
                & (DocumentChunk.chunk_index <= end)
            )
            for document_id, intervals in ranges.items()
            for start, end in intervals
        ]
        if not clauses:
            return []
        statement = (
            select(DocumentChunk, Document)
            .join(Document, Document.id == DocumentChunk.document_id)
            .where(or_(*clauses))
            .where(Document.source_type == "LIBRARY_EBOOK")
            .where(Document.metadata_["ingestion_status"].as_string() == "INDEXED")
            .where(Document.metadata_["embedding_version"].as_string() == self.settings.embedding_version)
            .where(DocumentChunk.metadata_["embedding_version"].as_string() == self.settings.embedding_version)
            .where(func.coalesce(DocumentChunk.metadata_["active"].as_boolean(), True).is_(True))
            .order_by(DocumentChunk.document_id, DocumentChunk.chunk_index)
        )
        async with self.session_factory() as session:
            rows = (await session.execute(statement)).all()
        neighbors: list[NeighborChunk] = []
        for chunk, document in rows:
            metadata = _current_chunk_metadata(chunk, document, self.settings.embedding_version)
            if metadata is not None:
                neighbors.append(NeighborChunk(
                    document_id=chunk.document_id, chunk_index=chunk.chunk_index,
                    text=chunk.content, vector_id=chunk.vector_id, metadata=metadata,
                ))
        return neighbors


class ContextExpander:
    """Append neighbouring evidence under its OWN stable citation ID/page.

    Joining a neighbouring page into the seed's text would misattribute that
    evidence to the seed's citation. Keep each chunk independently citable.
    """

    def __init__(self, *, source: NeighborChunkSource | None = None) -> None:
        self.source = source or PostgresNeighborChunkSource()

    async def expand(
        self,
        results: list[SearchResult],
        *,
        window: int = 1,
    ) -> list[SearchResult]:
        if type(window) is not int or not 0 <= window <= 3:
            raise ValueError("context expansion window must be an integer between 0 and 3.")
        if window == 0 or not results:
            return results

        identities: list[tuple | None] = []
        ranges: dict[int, list[tuple[int, int]]] = {}
        for result in results:
            metadata = result.metadata or {}
            scope = expansion_scope(metadata)
            chunk_index = resolve_chunk_index(metadata)
            if scope is None or chunk_index is None or not _vector_id_consistent(result.vector_id, metadata):
                identities.append(None)
                continue
            identities.append((scope, chunk_index))
            ranges.setdefault(scope.document_id, []).append((max(0, chunk_index - window), chunk_index + window))

        if not ranges:
            return results
        neighbors = await self.source.load(_merge_ranges(ranges))
        by_identity: dict[tuple[int, int], list[NeighborChunk]] = {}
        for neighbor in neighbors:
            if positive_int(neighbor.document_id) is None or resolve_chunk_index(
                neighbor.metadata, fallback=neighbor.chunk_index,
            ) is None:
                continue
            by_identity.setdefault((neighbor.document_id, neighbor.chunk_index), []).append(neighbor)

        expanded: list[SearchResult] = list(results)
        seen = {str(result.vector_id or result.id) for result in results}
        eligible: list[tuple] = []
        for seed_rank, (result, identity) in enumerate(zip(results, identities, strict=True)):
            if identity is None:
                continue
            scope, chunk_index = identity
            anchors = by_identity.get((scope.document_id, chunk_index), [])
            # A Qdrant seed can outlive a rebuild in PostgreSQL. Verify its
            # current DB anchor before borrowing anything from adjacent indexes.
            if len(anchors) != 1 or not _matches_anchor(result, anchors[0], scope):
                continue
            for direction in (-1, 1):
                for distance in range(1, window + 1):
                    index = chunk_index + direction * distance
                    candidates = by_identity.get((scope.document_id, index), [])
                    if len(candidates) != 1:
                        break
                    chunk = candidates[0]
                    if (not _valid_neighbor_text(chunk)
                            or expansion_scope(chunk.metadata, document_id=chunk.document_id) != scope):
                        break
                    relation = chapter_relation(result.metadata, chunk.metadata)
                    if relation is None:
                        break  # Never jump over a boundary, missing or stale chunk.
                    priority = 1 if relation == "same_page_fallback" else 0
                    eligible.append((priority, distance, seed_rank, index, relation, chunk, result))
        # All original ranked seeds remain first. Among optional neighbours,
        # prefer chapter/section-safe evidence, then distance, then seed rank.
        eligible.sort(key=lambda item: item[:4])
        for _, distance, _, _, relation, chunk, result in eligible:
            if chunk.vector_id in seen:
                continue
            seen.add(chunk.vector_id)
            metadata = dict(chunk.metadata)
            metadata.update({
                "context_expanded_from": result.vector_id,
                "context_expansion_relation": relation,
                "context_expansion_distance": distance,
                "score_type": "seed_cosine",
            })
            version = read_alias(metadata, ("embedding_version", "embeddingVersion"))
            metadata.update({"embedding_version": version, "document_id": chunk.document_id,
                             "chunk_index": chunk.chunk_index})
            expanded.append(SearchResult(
                id=deterministic_qdrant_point_id(f"{chunk.vector_id}:{version}"),
                vector_id=chunk.vector_id, score=result.score, text=chunk.text,
                metadata=metadata, retrieval_source="context_neighbor",
            ))
        return expanded


def _matches_anchor(seed: SearchResult, anchor: NeighborChunk, scope) -> bool:
    if (not _valid_neighbor_text(anchor) or anchor.vector_id != seed.vector_id or anchor.text != seed.text
            or expansion_scope(anchor.metadata, document_id=anchor.document_id) != scope
            or page_interval(anchor.metadata) != page_interval(seed.metadata)
            or chapter_relation(seed.metadata, anchor.metadata) is None):
        return False
    digest = hashlib.sha256(seed.text.encode("utf-8")).hexdigest()
    return all(metadata.get("chunk_hash", digest) == digest for metadata in (seed.metadata, anchor.metadata))


def _valid_neighbor_text(chunk: NeighborChunk) -> bool:
    digest = hashlib.sha256(chunk.text.encode("utf-8")).hexdigest()
    return (_vector_id_consistent(chunk.vector_id, chunk.metadata) and bool(chunk.text.strip())
            and chunk.metadata.get("chunk_hash", digest) == digest)


def _vector_id_consistent(vector_id: str | None, metadata: dict) -> bool:
    try:
        original = read_alias(metadata, ("vector_id",))
        return (text_value(vector_id) is not None and vector_id == text_value(vector_id)
                and (original is None or original == vector_id))
    except ValueError:
        return False


def _merge_ranges(ranges: dict[int, list[tuple[int, int]]]) -> dict[int, list[tuple[int, int]]]:
    merged: dict[int, list[tuple[int, int]]] = {}
    for document_id, intervals in ranges.items():
        output: list[tuple[int, int]] = []
        for start, end in sorted(intervals):
            if output and start <= output[-1][1] + 1:
                output[-1] = output[-1][0], max(output[-1][1], end)
            else:
                output.append((start, end))
        merged[document_id] = output
    return merged


def _current_chunk_metadata(chunk, document, embedding_version: str) -> dict | None:
    """Use authoritative row identity, but never relabel a stale chunk version."""

    metadata = dict(chunk.metadata_ or {})
    current = dict(document.metadata_ or {})
    try:
        if (metadata.get("active", True) is not True or document.source_type != "LIBRARY_EBOOK"
                or current.get("ingestion_status") != "INDEXED"
                or current.get("embedding_version") != embedding_version
                or read_alias(metadata, ("embedding_version", "embeddingVersion")) != embedding_version):
            return None
        identities = (
            (("document_id", "documentInternalId"), document.id, positive_int),
            (("ebook_id", "ebookId"), document.ebook_id, positive_int),
            (("book_id", "bookId"), document.book_id, positive_int),
            (("documentId", "external_document_id"), document.external_document_id, text_value),
            (("chunk_index", "chunkIndex"), chunk.chunk_index, non_negative_int),
            (("vector_id",), chunk.vector_id, text_value),
            (("sourceType",), document.source_type, text_value),
        )
        if chunk.document_id != document.id:
            return None
        for keys, value, parser in identities:
            original = read_alias(metadata, keys, parser)
            if original is not None and original != value:
                return None
            for key in keys:
                if value is not None:
                    metadata[key] = value
        for keys, parser in REVISION_FIELDS:
            current_value = read_alias(current, keys, parser)
            chunk_value = read_alias(metadata, keys, parser)
            # artifact_version is a manifest/storage label on document rows;
            # older ingestion did not put it on chunks. Do not backfill it or
            # confuse it with a raw-PDF revision. If present on a chunk, compare
            # it; source_checksum and declared source/policy versions stay strict.
            if keys[0] == "artifact_version" and chunk_value is None:
                continue
            if current_value is not None and chunk_value != current_value:
                return None
    except ValueError:
        return None
    return metadata if expansion_scope(metadata) is not None else None


class ContextCompressor:
    """Fit expanded evidence into one global character budget."""

    async def compress(
        self,
        query: str,
        results: list[SearchResult],
        *,
        max_context_chars: int,
    ) -> list[SearchResult]:
        if max_context_chars <= 0:
            return []
        remaining = max_context_chars
        query_tokens = set(tokenize(query))
        compressed: list[SearchResult] = []
        seen_text: set[str] = set()
        for result in results:
            if remaining <= 0:
                break
            source_text = (result.context_text or result.text).strip()
            normalized = " ".join(source_text.casefold().split())
            if not source_text or normalized in seen_text:
                continue
            seen_text.add(normalized)
            selected = _select_relevant_text(source_text, query_tokens, remaining)
            if not selected:
                continue
            compressed.append(replace(result, context_text=selected))
            remaining -= len(selected)
        return compressed


def _select_relevant_text(text: str, query_tokens: set[str], budget: int) -> str:
    if len(text) <= budget:
        return text
    segments = [segment.strip() for segment in re.split(r"(?<=[.!?])\s+|\n{2,}", text) if segment.strip()]
    ranked = sorted(
        enumerate(segments),
        key=lambda item: (
            -len(query_tokens.intersection(tokenize(item[1]))),
            item[0],
        ),
    )
    chosen: list[tuple[int, str]] = []
    used = 0
    for index, segment in ranked:
        separator = 1 if chosen else 0
        available = budget - used - separator
        if available <= 0:
            break
        clipped = segment[:available].strip()
        if clipped:
            chosen.append((index, clipped))
            used += len(clipped) + separator
    chosen.sort(key=lambda item: item[0])
    return " ".join(segment for _, segment in chosen)


def _positive_int(value) -> int | None:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _non_negative_int(value) -> int | None:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None


def _page_value(metadata: dict, *keys: str) -> int | None:
    for key in keys:
        value = metadata.get(key)
        if value is not None:
            return _positive_int(value)
    return None
