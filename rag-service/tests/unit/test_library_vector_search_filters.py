import pytest

from app.core.config import Settings
from app.indexing.search_filters import (
    LIBRARY_EBOOK_SOURCE_TYPE,
    LibraryVectorSearchScope,
    build_library_vector_filters,
    build_library_vector_search_query,
)


def _settings() -> Settings:
    settings = Settings(_env_file=None)
    settings.embedding_version = "gemini-embedding-2-3-test"
    return settings


def test_library_scope_requires_at_least_one_business_scope() -> None:
    with pytest.raises(ValueError, match="requires book_id, ebook_id, or document_id"):
        LibraryVectorSearchScope()


def test_library_vector_filters_include_mandatory_safety_filters() -> None:
    scope = LibraryVectorSearchScope(book_id=101, ebook_id=55)

    filters = build_library_vector_filters(scope, settings=_settings())

    assert filters == {
        "active": True,
        "sourceType": LIBRARY_EBOOK_SOURCE_TYPE,
        "embedding_version": "gemini-embedding-2-3-test",
        "book_id": 101,
        "ebook_id": 55,
    }


def test_library_vector_filters_allow_document_scope() -> None:
    scope = LibraryVectorSearchScope(document_id=7, embedding_version="custom-version")

    filters = build_library_vector_filters(scope, settings=_settings())

    assert filters["document_id"] == 7
    assert filters["embedding_version"] == "custom-version"
    assert filters["active"] is True


def test_library_vector_filters_reject_invalid_ids() -> None:
    scope = LibraryVectorSearchScope(book_id=0)

    with pytest.raises(ValueError, match="book_id"):
        build_library_vector_filters(scope, settings=_settings())


def test_build_library_vector_search_query_wraps_filters_in_query_object() -> None:
    query = build_library_vector_search_query(
        query_vector=[0.1, 0.2, 0.3],
        top_k=5,
        scope=LibraryVectorSearchScope(book_id=101),
        score_threshold=0.5,
        include_vectors=True,
        settings=_settings(),
    )

    assert query.query_vector == [0.1, 0.2, 0.3]
    assert query.top_k == 5
    assert query.score_threshold == 0.5
    assert query.include_vectors is True
    assert query.filters["active"] is True
    assert query.filters["book_id"] == 101
    assert query.filters["embedding_version"] == "gemini-embedding-2-3-test"
