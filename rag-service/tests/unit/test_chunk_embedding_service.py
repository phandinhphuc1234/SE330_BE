import pytest

from app.core.config import Settings
from app.indexing.embedding_service import ChunkEmbeddingService
from app.ingestion.chunkers.base import Chunk


class FakeEmbeddingProvider:
    provider_name = "gemini"
    model_name = "gemini-embedding-2"
    dimension = 3
    version = "gemini-embedding-2-3-test"

    def __init__(self, vectors: list[list[float]] | None = None, error: Exception | None = None) -> None:
        self.vectors = [[0.1, 0.2, 0.3]] if vectors is None else vectors
        self.error = error
        self.calls: list[list[str]] = []

    async def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(texts)
        if self.error is not None:
            raise self.error
        return self.vectors


def _settings() -> Settings:
    settings = Settings(_env_file=None)
    settings.embedding_dim = 3
    settings.embedding_version = "gemini-embedding-2-3-test"
    return settings


@pytest.mark.asyncio
async def test_embed_chunks_attaches_embedding_metadata_and_returns_vectors() -> None:
    provider = FakeEmbeddingProvider(vectors=[[0.1, 0.2, 0.3]])
    service = ChunkEmbeddingService(provider=provider, settings=_settings())
    chunk = Chunk(
        text="Minh bước vào thư viện.",
        metadata={
            "book_title": "Thư Viện Cuối Phố",
            "chapter_title": "Chương 1",
            "pageStart": 12,
            "pageEnd": 12,
            "vector_id": "doc-7-chunk-0-abc",
        },
    )

    embedded = await service.embed_chunks([chunk])

    assert len(embedded) == 1
    assert embedded[0].vector == [0.1, 0.2, 0.3]
    assert embedded[0].vector_id == "doc-7-chunk-0-abc"
    assert embedded[0].embedding_text.text.startswith("title: Thư Viện Cuối Phố | text:")
    assert provider.calls == [[embedded[0].embedding_text.text]]

    assert chunk.metadata["embedding_status"] == "embedded"
    assert chunk.metadata["embedding_provider"] == "gemini"
    assert chunk.metadata["embedding_model"] == "gemini-embedding-2"
    assert chunk.metadata["embedding_dim"] == 3
    assert chunk.metadata["embedding_version"] == "gemini-embedding-2-3-test"
    assert chunk.metadata["embedding_text_policy"] == "gemini_search_title_text_v1"
    assert len(chunk.metadata["embedding_text_hash"]) == 64
    assert chunk.metadata["embedding_vector_dimension"] == 3
    assert chunk.metadata["vector_indexing_status"] == "pending_qdrant_upsert"
    assert "embedding_vector" not in chunk.metadata


@pytest.mark.asyncio
async def test_embed_chunks_embeds_each_chunk_text_in_order() -> None:
    provider = FakeEmbeddingProvider(vectors=[[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]])
    service = ChunkEmbeddingService(provider=provider, settings=_settings())
    chunks = [
        Chunk(text="Đoạn một.", metadata={"title": "Sách A", "chunk_index": 0}),
        Chunk(text="Đoạn hai.", metadata={"title": "Sách A", "chunk_index": 1}),
    ]

    embedded = await service.embed_chunks(chunks)

    assert [item.vector for item in embedded] == [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]
    assert len(provider.calls[0]) == 2
    assert "Đoạn một." in provider.calls[0][0]
    assert "Đoạn hai." in provider.calls[0][1]


@pytest.mark.asyncio
async def test_embed_chunks_empty_input_does_not_call_provider() -> None:
    provider = FakeEmbeddingProvider()
    service = ChunkEmbeddingService(provider=provider, settings=_settings())

    assert await service.embed_chunks([]) == []
    assert provider.calls == []


@pytest.mark.asyncio
async def test_provider_error_marks_chunks_failed() -> None:
    provider = FakeEmbeddingProvider(error=RuntimeError("429 RESOURCE_EXHAUSTED"))
    service = ChunkEmbeddingService(provider=provider, settings=_settings())
    chunk = Chunk(text="Đoạn lỗi.", metadata={"title": "Sách A"})

    with pytest.raises(RuntimeError, match="RESOURCE_EXHAUSTED"):
        await service.embed_chunks([chunk])

    assert chunk.metadata["embedding_status"] == "failed"
    assert "RESOURCE_EXHAUSTED" in chunk.metadata["embedding_error"]
    assert chunk.metadata["vector_indexing_status"] == "embedding_failed"


@pytest.mark.asyncio
async def test_vector_count_mismatch_marks_chunks_failed() -> None:
    provider = FakeEmbeddingProvider(vectors=[])
    service = ChunkEmbeddingService(provider=provider, settings=_settings())
    chunk = Chunk(text="Đoạn lỗi.", metadata={"title": "Sách A"})

    with pytest.raises(ValueError, match="returned 0 vectors"):
        await service.embed_chunks([chunk])

    assert chunk.metadata["embedding_status"] == "failed"
    assert chunk.metadata["vector_indexing_status"] == "embedding_failed"


@pytest.mark.asyncio
async def test_dimension_mismatch_marks_chunks_failed() -> None:
    provider = FakeEmbeddingProvider(vectors=[[0.1, 0.2]])
    service = ChunkEmbeddingService(provider=provider, settings=_settings())
    chunk = Chunk(text="Đoạn lỗi.", metadata={"title": "Sách A"})

    with pytest.raises(ValueError, match="dimension mismatch"):
        await service.embed_chunks([chunk])

    assert chunk.metadata["embedding_status"] == "failed"
    assert chunk.metadata["vector_indexing_status"] == "embedding_failed"
