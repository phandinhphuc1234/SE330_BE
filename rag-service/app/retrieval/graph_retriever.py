"""Bounded graph traversal that returns source chunks, not generated graph facts."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import func, select

from app.core.config import get_settings
from app.db.session import async_session_factory
from app.documents.models import Document, DocumentChunk
from app.indexing import LibraryVectorSearchScope, SearchResult
from app.indexing.qdrant_store import deterministic_qdrant_point_id
from app.retrieval.graph_indexer import GRAPH_EXTRACTION_VERSION, normalize_entity
from app.retrieval.graph_models import DocumentGraphEdge
from app.retrieval.keyword_retriever import KeywordCandidate


@dataclass(frozen=True)
class GraphEdge:
    document_id: int
    source: str
    target: str
    relation: str
    chunk: KeywordCandidate


class PostgresGraphSource:
    def __init__(self, *, session_factory=async_session_factory, settings=None) -> None:
        self.session_factory = session_factory
        self.settings = settings or get_settings()

    async def load(self, scope: LibraryVectorSearchScope) -> list[GraphEdge]:
        statement = (select(DocumentGraphEdge, DocumentChunk, Document)
                     .join(DocumentChunk, DocumentChunk.id == DocumentGraphEdge.chunk_id)
                     .join(Document, Document.id == DocumentGraphEdge.document_id)
                     .where(DocumentChunk.document_id == Document.id)
                     .where(Document.source_type == scope.source_type)
                     .where(Document.metadata_["ingestion_status"].as_string() == "INDEXED")
                     .where(Document.metadata_["embedding_version"].as_string() == scope.embedding_version)
                     .where(DocumentGraphEdge.embedding_version == scope.embedding_version)
                     .where(DocumentGraphEdge.extraction_version == GRAPH_EXTRACTION_VERSION)
                     .where(func.coalesce(DocumentChunk.metadata_["active"].as_boolean(), True) == scope.active)
                     .order_by(DocumentGraphEdge.id).limit(self.settings.graph_edge_limit))
        for value, field in ((scope.document_id, Document.id), (scope.ebook_id, Document.ebook_id), (scope.book_id, Document.book_id)):
            if value is not None:
                statement = statement.where(field == value)
        async with self.session_factory() as session:
            rows = (await session.execute(statement)).all()
        return [GraphEdge(document.id, edge.source_key, edge.target_key, edge.relation,
                          KeywordCandidate(chunk.id, chunk.vector_id, chunk.content, {
                              **dict(chunk.metadata_ or {}), "document_id": document.id,
                              "documentId": document.external_document_id, "book_id": document.book_id,
                              "bookId": document.book_id, "ebook_id": document.ebook_id,
                              "ebookId": document.ebook_id, "chunk_index": chunk.chunk_index,
                              "embedding_version": scope.embedding_version, "vector_id": chunk.vector_id,
                          }))
                for edge, chunk, document in rows]


def traverse_graph(edges: list[GraphEdge], *, query: str, seed_ids: set[str], max_hops: int, limit: int) -> list[tuple[GraphEdge, int]]:
    """Traverse independently within each document; no cross-book edges."""
    query_terms = set(normalize_entity(query).split())
    by_document: dict[int, list[GraphEdge]] = {}
    for edge in edges:
        by_document.setdefault(edge.document_id, []).append(edge)
    matched = []
    for document_edges in by_document.values():
        frontier = set()
        for edge in document_edges:
            for node in (edge.source, edge.target):
                if set(node.split()).issubset(query_terms) or edge.chunk.vector_id in seed_ids:
                    frontier.add(node)
        seen_nodes = set(frontier)
        seen_edges = set()
        for hop in range(1, max_hops + 1):
            next_frontier = set()
            for index, edge in enumerate(document_edges):
                if index in seen_edges or not ({edge.source, edge.target} & frontier):
                    continue
                seen_edges.add(index)
                matched.append((edge, hop))
                next_frontier.update({edge.source, edge.target} - seen_nodes)
            seen_nodes.update(next_frontier)
            frontier = next_frontier
            if not frontier:
                break
    matched.sort(key=lambda item: (item[1], item[0].document_id, item[0].chunk.chunk_id))
    return matched[:limit]


class GraphRetriever:
    def __init__(self, *, source=None, settings=None) -> None:
        self.settings = settings or get_settings()
        self.source = source or PostgresGraphSource(settings=self.settings)

    async def search(self, query: str, top_k: int, *, scope: LibraryVectorSearchScope, seeds: list[SearchResult]) -> list[SearchResult]:
        edges = await self.source.load(scope)
        traversal = traverse_graph(edges, query=query, seed_ids={seed.vector_id or seed.id for seed in seeds[:2]},
                                   max_hops=self.settings.graph_max_hops, limit=self.settings.graph_edge_limit)
        seen = set()
        results = []
        for edge, hops in traversal:
            chunk = edge.chunk
            if not chunk.vector_id or chunk.vector_id in seen:
                continue
            seen.add(chunk.vector_id)
            results.append(SearchResult(
                id=deterministic_qdrant_point_id(f"{chunk.vector_id}:{scope.embedding_version}"),
                vector_id=chunk.vector_id, score=1.0 / hops, text=chunk.text,
                metadata={**chunk.metadata, "graph_hops": hops, "graph_relation": edge.relation},
                retrieval_source="graph",
            ))
            if len(results) >= top_k:
                break
        return results
