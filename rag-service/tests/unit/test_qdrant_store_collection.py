from types import SimpleNamespace
from uuid import UUID

import pytest

from app.core.config import Settings
from app.indexing.base import VectorChunk, VectorSearchQuery
from app.indexing.qdrant_store import (
    QdrantVectorStore,
    QdrantVectorStoreConfigError,
    deterministic_qdrant_point_id,
)


def _settings() -> Settings:
    settings = Settings(_env_file=None)
    settings.qdrant_collection_name = "rag_chunks_test"
    settings.embedding_dim = 3
    settings.embedding_distance_metric = "Cosine"
    return settings


class FakeQdrantClient:
    def __init__(self, *, exists: bool = True, collection_info=None) -> None:
        self.exists = exists
        self.collection_info = collection_info
        self.collection_exists_calls: list[str] = []
        self.get_collection_calls: list[str] = []
        self.create_collection_calls: list[dict] = []

    async def collection_exists(self, collection_name: str) -> bool:
        self.collection_exists_calls.append(collection_name)
        return self.exists

    async def get_collection(self, collection_name: str):
        self.get_collection_calls.append(collection_name)
        if self.collection_info is None:
            raise RuntimeError("collection not found")
        return self.collection_info

    async def create_collection(self, **kwargs) -> None:
        self.create_collection_calls.append(kwargs)


class FakeQdrantUpsertClient(FakeQdrantClient):
    def __init__(self, *, index_error: Exception | None = None) -> None:
        super().__init__(exists=True, collection_info=_collection_info(size=3, distance="Cosine"))
        self.index_error = index_error
        self.create_payload_index_calls: list[dict] = []
        self.upsert_calls: list[dict] = []
        self.search_calls: list[dict] = []

    async def create_payload_index(self, **kwargs) -> None:
        self.create_payload_index_calls.append(kwargs)
        if self.index_error is not None:
            raise self.index_error

    async def upsert(self, **kwargs) -> None:
        self.upsert_calls.append(kwargs)

    async def search(self, **kwargs):
        self.search_calls.append(kwargs)
        return [
            SimpleNamespace(
                id="9e16fe78-79ab-5b0c-b366-814df84c8bd2",
                score=0.82,
                payload={
                    "vector_id": "doc-7-chunk-0-abc",
                    "content": "Minh bước vào thư viện.",
                    "book_id": 101,
                    "ebook_id": 55,
                    "pageStart": 12,
                },
            )
        ]


class FakeQdrantQueryPointsClient(FakeQdrantClient):
    def __init__(self) -> None:
        super().__init__(exists=True, collection_info=_collection_info(size=3, distance="Cosine"))
        self.create_payload_index_calls: list[dict] = []
        self.search_calls: list[dict] = []

    async def create_payload_index(self, **kwargs) -> None:
        self.create_payload_index_calls.append(kwargs)

    async def query_points(self, **kwargs):
        self.search_calls.append(kwargs)
        return SimpleNamespace(
            points=[
                SimpleNamespace(
                    id="9e16fe78-79ab-5b0c-b366-814df84c8bd2",
                    score=0.77,
                    payload={
                        "vector_id": "doc-7-chunk-1-def",
                        "content": "Minh đọc một trang sách cũ.",
                        "book_id": 101,
                        "ebook_id": 55,
                        "pageStart": 13,
                    },
                )
            ]
        )


def _collection_info(*, size: int = 3, distance: str = "Cosine"):
    return SimpleNamespace(
        config=SimpleNamespace(
            params=SimpleNamespace(
                vectors=SimpleNamespace(size=size, distance=distance),
            )
        )
    )


@pytest.mark.asyncio
async def test_ensure_collection_creates_missing_collection() -> None:
    client = FakeQdrantClient(exists=False)
    store = QdrantVectorStore(settings=_settings(), client=client)

    await store.ensure_collection()

    assert client.collection_exists_calls == ["rag_chunks_test"]
    assert client.get_collection_calls == []
    assert len(client.create_collection_calls) == 1
    create_call = client.create_collection_calls[0]
    assert create_call["collection_name"] == "rag_chunks_test"
    assert create_call["vectors_config"].size == 3
    assert str(create_call["vectors_config"].distance.value).lower() == "cosine"


@pytest.mark.asyncio
async def test_ensure_collection_accepts_matching_existing_collection() -> None:
    client = FakeQdrantClient(exists=True, collection_info=_collection_info(size=3, distance="Cosine"))
    store = QdrantVectorStore(settings=_settings(), client=client)

    await store.ensure_collection()

    assert client.create_collection_calls == []
    assert client.get_collection_calls == ["rag_chunks_test"]


@pytest.mark.asyncio
async def test_ensure_collection_rejects_dimension_mismatch() -> None:
    client = FakeQdrantClient(exists=True, collection_info=_collection_info(size=1536, distance="Cosine"))
    store = QdrantVectorStore(settings=_settings(), client=client)

    with pytest.raises(QdrantVectorStoreConfigError, match="vector size mismatch"):
        await store.ensure_collection()


@pytest.mark.asyncio
async def test_ensure_collection_rejects_distance_mismatch() -> None:
    client = FakeQdrantClient(exists=True, collection_info=_collection_info(size=3, distance="Dot"))
    store = QdrantVectorStore(settings=_settings(), client=client)

    with pytest.raises(QdrantVectorStoreConfigError, match="distance mismatch"):
        await store.ensure_collection()


@pytest.mark.asyncio
async def test_ensure_collection_reads_dict_vector_config() -> None:
    collection_info = {
        "config": {
            "params": {
                "vectors": {
                    "size": 3,
                    "distance": "Cosine",
                }
            }
        }
    }
    client = FakeQdrantClient(exists=True, collection_info=collection_info)
    store = QdrantVectorStore(settings=_settings(), client=client)

    await store.ensure_collection()

    assert client.create_collection_calls == []


@pytest.mark.asyncio
async def test_upsert_builds_stable_point_id_and_retrieval_payload() -> None:
    client = FakeQdrantUpsertClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    chunk = VectorChunk(
        id="doc-7-chunk-0-abc",
        text="Minh bước vào thư viện.",
        vector=[0.1, 0.2, 0.3],
        metadata={
            "vector_id": "doc-7-chunk-0-abc",
            "document_id": 7,
            "documentId": "doc_ebook_55",
            "sourceType": "LIBRARY_EBOOK",
            "book_id": 101,
            "ebook_id": 55,
            "pageStart": 12,
            "pageEnd": 12,
            "chunk_index": 0,
            "chunk_hash": "abc",
            "chapter_title": "Chương 1",
            "token_count": 42,
            "embedding_model": "gemini-embedding-2",
            "embedding_dim": 3,
            "embedding_version": "gemini-embedding-2-3-test",
            "embedding_text_policy": "gemini_search_title_text_v1",
            "embedding_text_hash": "hash",
            # This is useful in PostgreSQL/artifacts, but should not bloat Qdrant payload.
            "chunk_quality_report": {"very": "large"},
        },
    )

    await store.upsert([chunk])

    expected_point_id = deterministic_qdrant_point_id("doc-7-chunk-0-abc:gemini-embedding-2-3-test")
    assert len(client.upsert_calls) == 1
    assert client.upsert_calls[0]["collection_name"] == "rag_chunks_test"
    assert client.upsert_calls[0]["wait"] is True

    point = client.upsert_calls[0]["points"][0]
    assert point.id == expected_point_id
    assert UUID(point.id)
    assert point.vector == [0.1, 0.2, 0.3]

    payload = point.payload
    assert payload["qdrant_point_id"] == expected_point_id
    assert payload["qdrant_point_key"] == "doc-7-chunk-0-abc:gemini-embedding-2-3-test"
    assert payload["vector_id"] == "doc-7-chunk-0-abc"
    assert payload["content"] == "Minh bước vào thư viện."
    assert payload["active"] is True
    assert payload["source_system"] == "LIBRARY"
    assert payload["document_id"] == 7
    assert payload["book_id"] == 101
    assert payload["ebook_id"] == 55
    assert payload["embedding_version"] == "gemini-embedding-2-3-test"
    assert "chunk_quality_report" not in payload


@pytest.mark.asyncio
async def test_upsert_creates_payload_indexes_for_filter_fields() -> None:
    client = FakeQdrantUpsertClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    chunk = VectorChunk(id="doc-7-chunk-0-abc", text="text", vector=[0.1, 0.2, 0.3], metadata={})

    await store.upsert([chunk])

    indexed_fields = {call["field_name"] for call in client.create_payload_index_calls}
    assert {"active", "document_id", "book_id", "ebook_id", "embedding_version", "sourceType"}.issubset(
        indexed_fields
    )


@pytest.mark.asyncio
async def test_payload_index_creation_ignores_existing_index_error() -> None:
    client = FakeQdrantUpsertClient(index_error=RuntimeError("payload index already exists"))
    store = QdrantVectorStore(settings=_settings(), client=client)

    await store.ensure_collection()

    assert client.create_payload_index_calls


@pytest.mark.asyncio
async def test_upsert_rejects_vector_dimension_mismatch_before_qdrant_call() -> None:
    client = FakeQdrantUpsertClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    chunk = VectorChunk(id="doc-7-chunk-0-abc", text="text", vector=[0.1, 0.2], metadata={})

    with pytest.raises(QdrantVectorStoreConfigError, match="Vector dimension mismatch"):
        await store.upsert([chunk])

    assert client.upsert_calls == []


def test_deterministic_qdrant_point_id_is_uuid_and_stable() -> None:
    first = deterministic_qdrant_point_id("doc-7-chunk-0-abc:gemini-embedding-2-3-test")
    second = deterministic_qdrant_point_id("doc-7-chunk-0-abc:gemini-embedding-2-3-test")
    different = deterministic_qdrant_point_id("doc-7-chunk-1-def:gemini-embedding-2-3-test")

    assert first == second
    assert first != different
    assert UUID(first)


@pytest.mark.asyncio
async def test_search_calls_qdrant_with_filter_and_maps_results() -> None:
    client = FakeQdrantUpsertClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    query = VectorSearchQuery(
        query_vector=[0.1, 0.2, 0.3],
        top_k=5,
        filters={
            "active": True,
            "sourceType": "LIBRARY_EBOOK",
            "book_id": 101,
            "embedding_version": "gemini-embedding-2-3-test",
        },
    )

    results = await store.search(query)

    assert len(results) == 1
    assert results[0].id == "9e16fe78-79ab-5b0c-b366-814df84c8bd2"
    assert results[0].vector_id == "doc-7-chunk-0-abc"
    assert results[0].score == 0.82
    assert results[0].text == "Minh bước vào thư viện."
    assert results[0].metadata["pageStart"] == 12
    assert results[0].retrieval_source == "vector"

    search_call = client.search_calls[0]
    assert search_call["collection_name"] == "rag_chunks_test"
    assert search_call["query_vector"] == [0.1, 0.2, 0.3]
    assert search_call["limit"] == 5
    assert search_call["score_threshold"] is None
    assert search_call["with_payload"] is True
    assert search_call["with_vectors"] is False

    must = {condition.key: condition for condition in search_call["query_filter"].must}
    assert must["active"].match.value is True
    assert must["sourceType"].match.value == "LIBRARY_EBOOK"
    assert must["book_id"].match.value == 101


@pytest.mark.asyncio
async def test_search_supports_new_qdrant_query_points_api() -> None:
    client = FakeQdrantQueryPointsClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    query = VectorSearchQuery(
        query_vector=[0.1, 0.2, 0.3],
        top_k=5,
        filters={
            "active": True,
            "sourceType": "LIBRARY_EBOOK",
            "book_id": 101,
            "embedding_version": "gemini-embedding-2-3-test",
        },
    )

    results = await store.search(query)

    assert len(results) == 1
    assert results[0].score == 0.77
    assert results[0].vector_id == "doc-7-chunk-1-def"
    assert results[0].text == "Minh đọc một trang sách cũ."

    search_call = client.search_calls[0]
    assert search_call["collection_name"] == "rag_chunks_test"
    assert search_call["query"] == [0.1, 0.2, 0.3]
    assert search_call["limit"] == 5
    assert search_call["score_threshold"] is None
    assert search_call["with_payload"] is True
    assert search_call["with_vectors"] is False


@pytest.mark.asyncio
async def test_search_rejects_query_dimension_mismatch_before_qdrant_call() -> None:
    client = FakeQdrantUpsertClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    query = VectorSearchQuery(query_vector=[0.1, 0.2], top_k=5, filters={"active": True})

    with pytest.raises(QdrantVectorStoreConfigError, match="Query vector dimension mismatch"):
        await store.search(query)

    assert client.search_calls == []


@pytest.mark.asyncio
async def test_search_rejects_empty_filters_before_qdrant_call() -> None:
    client = FakeQdrantUpsertClient()
    store = QdrantVectorStore(settings=_settings(), client=client)
    query = VectorSearchQuery(query_vector=[0.1, 0.2, 0.3], top_k=5)

    with pytest.raises(ValueError, match="requires at least one"):
        await store.search(query)

    assert client.search_calls == []
