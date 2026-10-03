import pytest

from app.api.internal.routes_retrieval import (
    InternalVectorSearchRequest,
    search_library_vectors,
)
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalHit,
    LibraryVectorRetrievalResponse,
)


class FakeRetrievalService:
    def __init__(self) -> None:
        self.requests = []

    async def search(self, request):
        self.requests.append(request)
        return LibraryVectorRetrievalResponse(
            query_text_hash="hash",
            query_text_policy="gemini_search_title_text_v1",
            embedding_version="gemini-embedding-2-3-test",
            top_k=request.top_k or 10,
            applied_filters={
                "active": True,
                "sourceType": "LIBRARY_EBOOK",
                "embedding_version": "gemini-embedding-2-3-test",
                "book_id": request.book_id,
            },
            results=[
                LibraryVectorRetrievalHit(
                    point_id="point-1",
                    vector_id="doc-7-chunk-0-abc",
                    score=0.91,
                    text="Một đoạn liên quan.",
                    citation={"bookId": request.book_id, "pageStart": 3},
                    metadata={"book_id": request.book_id},
                )
            ],
        )


@pytest.mark.asyncio
async def test_internal_vector_search_route_maps_payload_to_service_and_response() -> None:
    service = FakeRetrievalService()
    payload = InternalVectorSearchRequest.model_validate(
        {
            "query": "nhân vật chính làm gì?",
            "bookId": 101,
            "topK": 3,
        }
    )

    response = await search_library_vectors(payload, service=service)

    assert service.requests[0].query == "nhân vật chính làm gì?"
    assert service.requests[0].book_id == 101
    assert service.requests[0].top_k == 3
    assert response.model_dump(by_alias=True) == {
        "queryTextHash": "hash",
        "queryTextPolicy": "gemini_search_title_text_v1",
        "embeddingVersion": "gemini-embedding-2-3-test",
        "topK": 3,
        "resultCount": 1,
        "appliedFilters": {
            "active": True,
            "sourceType": "LIBRARY_EBOOK",
            "embedding_version": "gemini-embedding-2-3-test",
            "book_id": 101,
        },
        "results": [
            {
                "pointId": "point-1",
                "vectorId": "doc-7-chunk-0-abc",
                "score": 0.91,
                "text": "Một đoạn liên quan.",
                "citation": {"bookId": 101, "pageStart": 3},
                "metadata": {"book_id": 101},
            }
        ],
    }


def test_internal_vector_search_request_requires_scope() -> None:
    with pytest.raises(ValueError, match="bookId, ebookId, or documentId"):
        InternalVectorSearchRequest.model_validate({"query": "thiếu scope"})
