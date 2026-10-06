import pytest

from app.indexing.base import SearchResult, VectorSearchQuery


def test_vector_search_query_defaults_are_safe() -> None:
    first = VectorSearchQuery(query_vector=[0.1, 0.2], top_k=5)
    second = VectorSearchQuery(query_vector=[0.3, 0.4], top_k=10)

    first.filters["active"] = True

    assert first.filters == {"active": True}
    assert second.filters == {}
    assert first.score_threshold is None
    assert first.include_vectors is False


def test_vector_search_query_rejects_empty_vector() -> None:
    with pytest.raises(ValueError, match="query_vector"):
        VectorSearchQuery(query_vector=[], top_k=5)


def test_vector_search_query_rejects_invalid_top_k() -> None:
    with pytest.raises(ValueError, match="top_k"):
        VectorSearchQuery(query_vector=[0.1, 0.2], top_k=0)


def test_search_result_defaults_to_vector_source() -> None:
    result = SearchResult(
        id="qdrant-point-id",
        vector_id="doc-7-chunk-0-abc",
        score=0.82,
        text="Relevant chunk",
        metadata={"pageStart": 1},
    )

    assert result.retrieval_source == "vector"
    assert result.metadata["pageStart"] == 1
