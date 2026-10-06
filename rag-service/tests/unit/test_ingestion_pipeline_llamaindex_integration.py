from types import SimpleNamespace

import pytest

from app.core.exceptions import IngestionError
from app.ingestion.llama_index import LLAMAINDEX_SENTENCE_CHUNKER
from app.ingestion.parsers.base import ParsedDocument
from app.ingestion.pipeline import IngestionPipeline


def source_document() -> SimpleNamespace:
    return SimpleNamespace(
        id=7,
        external_document_id="doc_ebook_55",
        source_type="LIBRARY_EBOOK",
        source_id="ebook:55",
        book_id=101,
        ebook_id=55,
        filename="original.pdf",
    )


def test_build_chunks_uses_llamaindex_cleaning_and_sentence_nodes() -> None:
    narrative_text = (
        "Page 1\n\n"
        "The efﬁcient   workﬂow keeps citation metadata while Minh walks through the quiet library. "
        "The old shelves lean toward the window, and every book seems to remember a different season. "
        "He pauses near the last aisle because a black volume has slipped from its place.\n\n"
        "When he touches the cover, the room grows colder. "
        "Outside, the rain softens into a thin silver line, and Minh suddenly feels that someone is waiting "
        "for him between the pages.\n1"
    )
    raw_documents = [
        ParsedDocument(
            text=narrative_text,
            metadata={
                "page_number": 1,
                "parser": "pymupdf4llm",
            },
        )
    ]

    chunks = IngestionPipeline()._build_chunks(raw_documents, document=source_document())

    assert len(chunks) == 1
    assert chunks[0].text.startswith("The efficient workflow keeps citation metadata")
    assert "Minh walks through the quiet library" in chunks[0].text
    metadata = chunks[0].metadata
    assert metadata["document_id"] == 7
    assert metadata["documentId"] == "doc_ebook_55"
    assert metadata["sourceType"] == "LIBRARY_EBOOK"
    assert metadata["source_type"] == "pdf"
    assert metadata["bookId"] == 101
    assert metadata["ebookId"] == 55
    assert metadata["page_number"] == 1
    assert metadata["pageStart"] == 1
    assert metadata["pageEnd"] == 1
    assert metadata["parser"] == "pymupdf4llm"
    assert metadata["cleaning_version"] == "pdf-clean-v1.0.0"
    assert metadata["cleaning_quality_status"] == "GOOD"
    assert metadata["document_profile"] == "novel_narrative"
    assert metadata["document_profile_version"] == "v1"
    assert "document_profile_signals" in metadata
    assert metadata["chunking_strategy"] == "library_pdf_narrative"
    assert metadata["chunking_strategy_version"] == "v1"
    assert metadata["chunk_level"] == "child"
    assert metadata["token_count"] > 0
    assert metadata["token_counter"] == "approx_whitespace_char_v1"
    assert metadata["chunk_quality_status"] == "PASS"
    assert metadata["chunk_quality_report"]["status"] == "PASS"
    assert metadata["chunk_quality_report"]["chunk_count"] == 1
    assert metadata["chunk_quality_report"]["min_chunk_tokens"] == metadata["token_count"]
    assert metadata["chunk_quality_report"]["max_chunk_tokens"] == metadata["token_count"]
    assert metadata["chunker"] == LLAMAINDEX_SENTENCE_CHUNKER
    assert metadata["chunk_index"] == 0
    assert metadata["chunkIndex"] == 0
    assert len(metadata["chunk_hash"]) == 64
    assert metadata["vector_id"].startswith("doc-7-chunk-0-")
    assert metadata["embedding_status"] == "pending"


def test_build_chunks_rejects_cleaning_quality_failed() -> None:
    raw_documents = [
        ParsedDocument(
            text="Page 1",
            metadata={"page_number": 1},
        )
    ]

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._build_chunks(raw_documents, document=source_document())

    assert exc_info.value.error_code == "PDF_CLEANING_QUALITY_FAILED"


def test_build_chunks_attaches_chapter_metadata_to_narrative_chunks() -> None:
    raw_documents = [
        ParsedDocument(
            text=(
                "Chương 1\n\n"
                "Minh bước vào thư viện khi thành phố vừa lên đèn. "
                "Những kệ sách tối màu kéo dài đến cuối căn phòng, còn tiếng mưa thì gõ nhẹ lên cửa kính.\n\n"
                "Cậu dừng lại trước một cuốn sách nằm lệch khỏi hàng, như thể nó đã chờ cậu từ rất lâu."
            ),
            metadata={"page_number": 1, "parser": "pymupdf4llm"},
        ),
        ParsedDocument(
            text=(
                "Sang trang sau, hành lang im lặng hơn. "
                "Minh nghe tiếng bước chân của chính mình vang lên giữa những bức tường phủ bụi.\n\n"
                "Cậu không thấy ai ở đó, nhưng cảm giác có người đang nhìn mình từ phía sau không biến mất."
            ),
            metadata={"page_number": 2, "parser": "pymupdf4llm"},
        ),
    ]

    chunks = IngestionPipeline()._build_chunks(raw_documents, document=source_document())

    page_one_chunks = [chunk for chunk in chunks if chunk.metadata["page_number"] == 1]
    page_two_chunks = [chunk for chunk in chunks if chunk.metadata["page_number"] == 2]
    assert page_one_chunks
    assert page_two_chunks

    first_page_metadata = page_one_chunks[0].metadata
    assert first_page_metadata["chapter_detection_version"] == "v1"
    assert first_page_metadata["chapter_detected"] is True
    assert first_page_metadata["chapter_detected_on_page"] is True
    assert first_page_metadata["chapter_index"] == 1
    assert first_page_metadata["chapter_number"] == "1"
    assert first_page_metadata["chapter_title"] == "Chương 1"
    assert first_page_metadata["chapter_source_page"] == 1
    assert first_page_metadata["section_path"] == ["Chương 1"]

    second_page_metadata = page_two_chunks[0].metadata
    assert second_page_metadata["chapter_detected"] is True
    assert second_page_metadata["chapter_detected_on_page"] is False
    assert second_page_metadata["chapter_index"] == 1
    assert second_page_metadata["chapter_title"] == "Chương 1"
    assert second_page_metadata["section_path"] == ["Chương 1"]


def test_build_chunks_enforces_max_chunks_per_document(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_settings",
        lambda: SimpleNamespace(
            chunk_size=32,
            chunk_overlap=4,
            max_chunks_per_document=1,
        ),
    )
    text = (
        "The first sentence explains upload validation. "
        "The second sentence explains PDF cleaning. "
        "The third sentence explains sentence chunking. "
        "The fourth sentence explains vector metadata. "
    ) * 6
    raw_documents = [
        ParsedDocument(
            text=text,
            metadata={"page_number": 1},
        )
    ]

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._build_chunks(raw_documents, document=source_document())

    assert exc_info.value.error_code == "MAX_CHUNKS_EXCEEDED"
