import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.retrieval.graph_indexer import GraphIndexer


def sql_result(*, document=None, chunks=None):
    return SimpleNamespace(scalar_one_or_none=lambda: document, scalar_one=lambda: document,
                           scalars=lambda: iter(chunks or []))


def setup_indexer(*, changed=False):
    document = SimpleNamespace(id=1, metadata_={"ingestion_status": "INDEXED", "embedding_version": "v1"})
    chunk = SimpleNamespace(id=1, vector_id="a", content="Della sold her hair.")
    replacement = SimpleNamespace(id=2, vector_id="b", content="Changed content.")
    additions = []
    session = SimpleNamespace(execute=AsyncMock(side_effect=[
        sql_result(document=document), sql_result(chunks=[chunk]),
        sql_result(document=document), sql_result(chunks=[replacement if changed else chunk]), None,
    ]), add=additions.append, commit=AsyncMock())
    context = AsyncMock()
    context.__aenter__.return_value = session
    llm = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({"relations": [{
        "source": "Della", "target": "hair", "relation": "sells", "chunkId": "a", "quote": chunk.content,
    }]})))
    indexer = GraphIndexer(session_factory=lambda: context, llm_client=llm,
                           settings=Settings(_env_file=None, embedding_version="v1"))
    return indexer, session, additions


@pytest.mark.asyncio
async def test_graph_publish_is_atomic_and_edges_have_original_chunk_fk():
    indexer, session, additions = setup_indexer()
    result = await indexer.build(10)
    assert result["edgeCount"] == 1
    assert additions[0].document_id == 1 and additions[0].chunk_id == 1
    assert additions[0].evidence_quote == "Della sold her hair."
    session.commit.assert_awaited_once()


@pytest.mark.asyncio
async def test_graph_build_refuses_publish_if_ingestion_replaces_chunks():
    indexer, session, additions = setup_indexer(changed=True)
    with pytest.raises(ValueError, match="changed"):
        await indexer.build(10)
    assert additions == []
    session.commit.assert_not_awaited()
    assert session.execute.await_count == 4  # No DELETE was executed.
