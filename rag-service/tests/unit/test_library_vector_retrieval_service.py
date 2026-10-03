import pytest

from app.core.config import Settings
from app.indexing.base import SearchResult
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalRequest,
    LibraryVectorRetrievalService,
    MAX_LIBRARY_RETRIEVAL_TOP_K,
)


def _settings() -> Settings:
    settings = Settings(_env_file=None)
    settings.embedding_dim = 3
    settings.embedding_version = "gemini-embedding-2-3-test"
    settings.retrieval_top_k = 7
    return settings


class FakeQueryEmbeddingProvider:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def embed_query(self, query_text: str) -> list[float]:
        self.calls.append(query_text)
        return [0.1, 0.2, 0.3]


class FakeVectorStore:
    def __init__(self) -> None:
        self.queries = []

    async def search(self, query):
        self.queries.append(query)
        return [
            SearchResult(
                id="point-1",
                vector_id="doc-7-chunk-0-abc",
                score=0.87,
                text="Minh tìm thấy cuốn sách bị mất.",
                metadata={
                    "documentId": "doc_ebook_55",
                    "document_id": 7,
                    "sourceType": "LIBRARY_EBOOK",
                    "book_id": 101,
                    "ebook_id": 55,
                    "chapter_title": "Chương 1",
                    "pageStart": 12,
                    "pageEnd": 13,
                    "chunk_index": 0,
                    "vector_id": "doc-7-chunk-0-abc",
                },
            )
        ]


@pytest.mark.asyncio
async def test_library_vector_retrieval_embeds_query_and_searches_with_mandatory_filters() -> None:
    provider = FakeQueryEmbeddingProvider()
    vector_store = FakeVectorStore()
    service = LibraryVectorRetrievalService(
        embedding_provider=provider,
        vector_store=vector_store,
        settings=_settings(),
    )

    response = await service.search(
        LibraryVectorRetrievalRequest(
            query="Minh tìm thấy gì?",
            book_id=101,
            ebook_id=55,
            top_k=5,
        )
    )

    assert provider.calls == ["task: search result | query: Minh tìm thấy gì?"]
    assert len(vector_store.queries) == 1
    vector_query = vector_store.queries[0]
    assert vector_query.query_vector == [0.1, 0.2, 0.3]
    assert vector_query.top_k == 5
    assert vector_query.filters == {
        "active": True,
        "sourceType": "LIBRARY_EBOOK",
        "embedding_version": "gemini-embedding-2-3-test",
        "book_id": 101,
        "ebook_id": 55,
    }

    assert response.embedding_version == "gemini-embedding-2-3-test"
    assert response.result_count == 1
    assert response.results[0].point_id == "point-1"
    assert response.results[0].vector_id == "doc-7-chunk-0-abc"
    assert response.results[0].citation == {
        "documentId": "doc_ebook_55",
        "documentInternalId": 7,
        "bookId": 101,
        "ebookId": 55,
        "chapterTitle": "Chương 1",
        "pageStart": 12,
        "pageEnd": 13,
        "chunkIndex": 0,
        "vectorId": "doc-7-chunk-0-abc",
    }


@pytest.mark.asyncio
async def test_library_vector_retrieval_uses_default_top_k() -> None:
    vector_store = FakeVectorStore()
    service = LibraryVectorRetrievalService(
        embedding_provider=FakeQueryEmbeddingProvider(),
        vector_store=vector_store,
        settings=_settings(),
    )

    await service.search(LibraryVectorRetrievalRequest(query="câu hỏi", book_id=101))

    assert vector_store.queries[0].top_k == 7


@pytest.mark.asyncio
async def test_library_vector_retrieval_rejects_missing_scope() -> None:
    service = LibraryVectorRetrievalService(
        embedding_provider=FakeQueryEmbeddingProvider(),
        vector_store=FakeVectorStore(),
        settings=_settings(),
    )

    with pytest.raises(ValueError, match="requires book_id, ebook_id, or document_id"):
        await service.search(LibraryVectorRetrievalRequest(query="câu hỏi"))


@pytest.mark.asyncio
async def test_library_vector_retrieval_rejects_large_top_k() -> None:
    service = LibraryVectorRetrievalService(
        embedding_provider=FakeQueryEmbeddingProvider(),
        vector_store=FakeVectorStore(),
        settings=_settings(),
    )

    with pytest.raises(ValueError, match=f"<= {MAX_LIBRARY_RETRIEVAL_TOP_K}"):
        await service.search(
            LibraryVectorRetrievalRequest(
                query="câu hỏi",
                book_id=101,
                top_k=MAX_LIBRARY_RETRIEVAL_TOP_K + 1,
            )
        )
