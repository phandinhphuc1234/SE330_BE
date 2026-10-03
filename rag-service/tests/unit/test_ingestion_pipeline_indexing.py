import pytest

from app.core.exceptions import IngestionError
from app.indexing.embedding_service import EmbeddedChunk
from app.indexing.embedding_text_builder import BuiltEmbeddingText
from app.indexing.qdrant_store import deterministic_qdrant_point_id
from app.ingestion.chunkers.base import Chunk
from app.ingestion.pipeline import IngestionPipeline


class FakeEmbeddingService:
    def __init__(self) -> None:
        self.calls: list[list[Chunk]] = []

    async def embed_chunks(self, chunks: list[Chunk]) -> list[EmbeddedChunk]:
        self.calls.append(chunks)
        embedded_chunks: list[EmbeddedChunk] = []
        for chunk in chunks:
            metadata = chunk.metadata or {}
            metadata.update(
                {
                    "embedding_status": "embedded",
                    "embedding_provider": "gemini",
                    "embedding_model": "gemini-embedding-2",
                    "embedding_dim": 3,
                    "embedding_version": "gemini-embedding-2-3-test",
                    "embedding_text_policy": "gemini_search_title_text_v1",
                    "embedding_text_hash": "embedding-text-hash",
                    "vector_indexing_status": "pending_qdrant_upsert",
                }
            )
            chunk.metadata = metadata
            embedded_chunks.append(
                EmbeddedChunk(
                    chunk=chunk,
                    vector=[0.1, 0.2, 0.3],
                    embedding_text=BuiltEmbeddingText(
                        text=f"title: none | text: {chunk.text}",
                        text_hash="embedding-text-hash",
                        policy="gemini_search_title_text_v1",
                    ),
                    vector_id=metadata.get("vector_id"),
                )
            )
        return embedded_chunks


class FakeVectorStore:
    collection_name = "rag_chunks_test"

    def __init__(self) -> None:
        self.upsert_calls = []

    async def upsert(self, vector_chunks) -> None:
        self.upsert_calls.append(vector_chunks)


@pytest.mark.asyncio
async def test_index_chunks_embeds_upserts_and_marks_qdrant_metadata() -> None:
    embedding_service = FakeEmbeddingService()
    vector_store = FakeVectorStore()
    pipeline = IngestionPipeline(
        embedding_service=embedding_service,
        vector_store=vector_store,
    )
    chunk = Chunk(
        text="Minh bước vào thư viện.",
        metadata={
            "vector_id": "doc-7-chunk-0-abc",
            "document_id": 7,
            "book_id": 101,
            "ebook_id": 55,
        },
    )

    result = await pipeline._index_chunks([chunk])

    assert result.chunk_count == 1
    assert result.vector_count == 1
    assert result.collection_name == "rag_chunks_test"
    assert result.embedding_model == "gemini-embedding-2"
    assert result.embedding_version == "gemini-embedding-2-3-test"
    assert embedding_service.calls == [[chunk]]

    vector_chunks = vector_store.upsert_calls[0]
    assert len(vector_chunks) == 1
    assert vector_chunks[0].id == "doc-7-chunk-0-abc"
    assert vector_chunks[0].text == "Minh bước vào thư viện."
    assert vector_chunks[0].vector == [0.1, 0.2, 0.3]
    assert vector_chunks[0].metadata["embedding_status"] == "embedded"

    expected_point_key = "doc-7-chunk-0-abc:gemini-embedding-2-3-test"
    assert chunk.metadata["vector_indexing_status"] == "indexed"
    assert chunk.metadata["qdrant_collection"] == "rag_chunks_test"
    assert chunk.metadata["qdrant_point_key"] == expected_point_key
    assert chunk.metadata["qdrant_point_id"] == deterministic_qdrant_point_id(expected_point_key)


@pytest.mark.asyncio
async def test_upsert_embedded_chunks_rejects_missing_vector_id() -> None:
    pipeline = IngestionPipeline(vector_store=FakeVectorStore())
    chunk = Chunk(text="Không có vector id.", metadata={})
    embedded = EmbeddedChunk(
        chunk=chunk,
        vector=[0.1, 0.2, 0.3],
        embedding_text=BuiltEmbeddingText(
            text="title: none | text: Không có vector id.",
            text_hash="hash",
            policy="gemini_search_title_text_v1",
        ),
        vector_id=None,
    )

    with pytest.raises(IngestionError) as exc_info:
        await pipeline._upsert_embedded_chunks([embedded])

    assert exc_info.value.error_code == "VECTOR_ID_MISSING"
