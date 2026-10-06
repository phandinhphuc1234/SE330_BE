import pytest

from app.core.config import Settings
from app.indexing.embedding_text_builder import (
    GEMINI_SEARCH_TITLE_TEXT_POLICY,
    EmbeddingTextBuilder,
)


def _settings() -> Settings:
    return Settings(_env_file=None)


def test_build_document_text_adds_novel_context_for_gemini() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    result = builder.build_document_text(
        "Minh bước vào căn phòng tối.\n\nTrên bàn là một phong thư cũ.",
        {
            "book_title": "Thư Viện Cuối Phố",
            "author": "Nguyễn An",
            "chapter_title": "Chương 1",
            "pageStart": 12,
            "pageEnd": 13,
        },
    )

    assert result.policy == GEMINI_SEARCH_TITLE_TEXT_POLICY
    assert result.title == "Thư Viện Cuối Phố"
    assert result.text == (
        "title: Thư Viện Cuối Phố | text: "
        "Book: Thư Viện Cuối Phố\n"
        "Author: Nguyễn An\n"
        "Chapter: Chương 1\n"
        "Page: 12-13\n\n"
        "Content:\n"
        "Minh bước vào căn phòng tối.\n\n"
        "Trên bàn là một phong thư cũ."
    )
    assert len(result.text_hash) == 64


def test_build_document_text_uses_section_path_when_available() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    result = builder.build_document_text(
        "Một tiếng động vang lên từ tầng hai.",
        {
            "title": "Căn Nhà Cũ",
            "section_path": ["Phần I", "Chương 2"],
            "page_number": 7,
        },
    )

    assert "Book: Căn Nhà Cũ" in result.text
    assert "Section: Phần I > Chương 2" in result.text
    assert "Page: 7" in result.text


def test_build_document_text_does_not_include_technical_metadata() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    result = builder.build_document_text(
        "Nội dung cần tìm kiếm.",
        {
            "bookTitle": "Sách A",
            "document_id": 99,
            "vector_id": "doc-99-chunk-1",
            "chunk_hash": "abc",
            "object_key": "ebooks/1/original.pdf",
            "checksum_sha256": "secret-hash",
        },
    )

    assert "Sách A" in result.text
    assert "Nội dung cần tìm kiếm." in result.text
    assert "document_id" not in result.text
    assert "doc-99-chunk-1" not in result.text
    assert "object_key" not in result.text
    assert "secret-hash" not in result.text


def test_build_query_text_uses_gemini_search_instruction() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    result = builder.build_query_text("  nhân vật chính gặp ai ở chương 1?\n")

    assert result.text == "task: search result | query: nhân vật chính gặp ai ở chương 1?"
    assert result.policy == GEMINI_SEARCH_TITLE_TEXT_POLICY
    assert len(result.text_hash) == 64


def test_document_text_hash_changes_when_text_changes() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    first = builder.build_document_text("Đoạn một.", {"title": "Sách A"})
    second = builder.build_document_text("Đoạn hai.", {"title": "Sách A"})

    assert first.text_hash != second.text_hash


def test_empty_chunk_fails_fast() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    with pytest.raises(ValueError, match="empty chunk"):
        builder.build_document_text("   ", {"title": "Sách A"})


def test_empty_query_fails_fast() -> None:
    builder = EmbeddingTextBuilder(settings=_settings())

    with pytest.raises(ValueError, match="empty query"):
        builder.build_query_text("\n  ")
