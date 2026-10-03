import pytest

from app.indexing.qdrant_filters import QdrantFilterBuildError, build_qdrant_filter


def _must_by_key(qdrant_filter) -> dict:
    return {condition.key: condition for condition in qdrant_filter.must}


def test_build_qdrant_filter_builds_must_exact_match_conditions() -> None:
    qdrant_filter = build_qdrant_filter(
        {
            "active": True,
            "sourceType": "LIBRARY_EBOOK",
            "book_id": 101,
            "embedding_version": "gemini-embedding-2-3-test",
        }
    )

    must = _must_by_key(qdrant_filter)
    assert must["active"].match.value is True
    assert must["sourceType"].match.value == "LIBRARY_EBOOK"
    assert must["book_id"].match.value == 101
    assert must["embedding_version"].match.value == "gemini-embedding-2-3-test"


def test_build_qdrant_filter_supports_match_any_for_lists() -> None:
    qdrant_filter = build_qdrant_filter({"book_id": [101, 102]})

    condition = _must_by_key(qdrant_filter)["book_id"]
    assert condition.match.any == [101, 102]


def test_build_qdrant_filter_rejects_empty_filters() -> None:
    with pytest.raises(QdrantFilterBuildError, match="requires at least one"):
        build_qdrant_filter({})


def test_build_qdrant_filter_rejects_nested_dict_value() -> None:
    with pytest.raises(QdrantFilterBuildError, match="Unsupported"):
        build_qdrant_filter({"book_id": {"eq": 101}})


def test_build_qdrant_filter_drops_none_and_blank_values() -> None:
    qdrant_filter = build_qdrant_filter(
        {
            "active": True,
            "empty": "",
            "none": None,
        }
    )

    must = _must_by_key(qdrant_filter)
    assert set(must) == {"active"}
