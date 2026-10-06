from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.indexing import LibraryVectorSearchScope
from app.retrieval.graph_indexer import ExtractedGraph, validated_relations
from app.retrieval.graph_retriever import GraphEdge, GraphRetriever, PostgresGraphSource, traverse_graph
from app.retrieval.keyword_retriever import KeywordCandidate


def edge(doc, index, source, target):
    return GraphEdge(doc, source, target, "knows", KeywordCandidate(index, f"d{doc}-c{index}", "Source quote",
                    {"document_id": doc, "ebook_id": doc, "pageStart": index + 1}))


def test_graph_extraction_rejects_fabricated_quote_foreign_source_and_self_edge():
    draft = ExtractedGraph.model_validate({"relations": [
        {"source": "Della", "target": "hair", "relation": "sells", "chunkId": "a", "quote": "Della sold her hair."},
        {"source": "Jim", "target": "watch", "relation": "sells", "chunkId": "other-book", "quote": "Jim sold the watch."},
        {"source": "Della", "target": "car", "relation": "owns", "chunkId": "a", "quote": "Della owns a car."},
        {"source": "Della", "target": "Della", "relation": "is", "chunkId": "a", "quote": "Della sold her hair."},
    ]})
    accepted = validated_relations(draft, {"a": "Della sold her hair."})
    assert len(accepted) == 1
    assert accepted[0].target == "hair"


def test_graph_bfs_respects_hop_limit_and_document_boundary():
    edges = [edge(1, 1, "della", "jim"), edge(1, 2, "jim", "watch"),
             edge(1, 3, "watch", "grandfather"), edge(2, 4, "jim", "private")]
    one_hop = traverse_graph(edges, query="della", seed_ids=set(), max_hops=1, limit=10)
    two_hops = traverse_graph(edges, query="della", seed_ids=set(), max_hops=2, limit=10)
    assert len(one_hop) == 1
    assert [(item.chunk.chunk_id, hop) for item, hop in two_hops] == [(1, 1), (2, 2)]


@pytest.mark.asyncio
async def test_graph_returns_original_chunk_citations_and_deduplicates_edges():
    source = SimpleNamespace(load=AsyncMock(return_value=[edge(1, 1, "della", "jim"), edge(1, 1, "della", "hair")]))
    retriever = GraphRetriever(source=source, settings=Settings(_env_file=None))
    scope = LibraryVectorSearchScope(ebook_id=1, embedding_version="v1")
    results = await retriever.search("della", 5, scope=scope, seeds=[])
    source.load.assert_awaited_once_with(scope)
    assert len(results) == 1
    assert results[0].vector_id == "d1-c1"
    assert results[0].metadata["pageStart"] == 2


@pytest.mark.asyncio
async def test_graph_sql_intersects_all_scopes_and_filters_stale_graph_version():
    session = SimpleNamespace(execute=AsyncMock(return_value=SimpleNamespace(all=lambda: [])))
    context = AsyncMock()
    context.__aenter__.return_value = session
    source = PostgresGraphSource(session_factory=lambda: context, settings=Settings(_env_file=None))
    await source.load(LibraryVectorSearchScope(book_id=100, ebook_id=10, document_id=1, embedding_version="v1"))
    from sqlalchemy.dialects import postgresql
    sql = str(session.execute.call_args.args[0].compile(dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}))
    for required in ("documents.ebook_id = 10", "documents.book_id = 100", "documents.id = 1",
                     "INDEXED", "document_graph_edges.embedding_version = 'v1'"):
        assert required in sql
