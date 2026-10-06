"""Cross-module regression: parsing metadata remains independently persisted."""

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.documents.repository import create_document_chunk, update_document_chunk_metadata


@pytest.mark.asyncio
async def test_chunk_metadata_does_not_alias_mutable_ingestion_state():
    session = SimpleNamespace(add=lambda item: None, flush=AsyncMock())
    initial = {"embedding_status": "pending", "nested": {"stage": "parsed"}}
    chunk = await create_document_chunk(session, 1, 0, "Evidence", metadata=initial)
    initial["embedding_status"] = "embedded"
    initial["nested"]["stage"] = "indexed"
    assert chunk.metadata_["embedding_status"] == "pending"
    assert chunk.metadata_["nested"]["stage"] == "parsed"
    await update_document_chunk_metadata(session, chunk, metadata=initial, vector_id="vector")
    assert chunk.metadata_["embedding_status"] == "embedded"
    initial["embedding_status"] = "failed"
    assert chunk.metadata_["embedding_status"] == "embedded"
