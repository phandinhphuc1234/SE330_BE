from llama_index.core import Document

from app.ingestion.profiling import (
    MIXED_UNKNOWN_PROFILE,
    NOVEL_NARRATIVE_PROFILE,
    SCANNED_OR_OCR_REQUIRED_PROFILE,
    DocumentProfiler,
)


def test_document_profiler_detects_novel_narrative_text() -> None:
    documents = [
        Document(
            text=(
                "Chương 1\n\n"
                "Minh bước vào thư viện khi trời vừa tối. Mùi giấy cũ lan nhẹ trong không khí.\n\n"
                "Ở cuối dãy sách, một cuốn sách màu đen nằm lệch khỏi kệ. "
                "Cậu đưa tay chạm vào nó và nghe tiếng mưa xa dần ngoài phố.\n\n"
                "Không ai nói gì. Căn phòng im lặng như thể đang chờ một câu chuyện bắt đầu."
            ),
            metadata={"page_number": 1},
        ),
        Document(
            text=(
                "Sáng hôm sau, thành phố phủ một lớp sương mỏng.\n\n"
                "Minh vẫn nhớ cảm giác lạnh buốt trên bìa sách. "
                "Cậu tự nhủ rằng mình sẽ không quay lại, nhưng đôi chân lại dẫn cậu đến đó.\n\n"
                "Người thủ thư nhìn cậu rất lâu rồi mỉm cười."
            ),
            metadata={"page_number": 2},
        ),
    ]

    profile = DocumentProfiler().profile(documents)

    assert profile.name == NOVEL_NARRATIVE_PROFILE
    assert profile.version == "v1"
    assert profile.confidence > 0.6
    assert profile.signals["heading_count"] == 1
    assert profile.signals["table_count"] == 0
    assert profile.to_metadata()["document_profile"] == NOVEL_NARRATIVE_PROFILE


def test_document_profiler_flags_ocr_like_low_text_document() -> None:
    profile = DocumentProfiler().profile(
        [
            Document(text="12", metadata={"page_number": 1}),
            Document(text="", metadata={"page_number": 2}),
        ]
    )

    assert profile.name == SCANNED_OR_OCR_REQUIRED_PROFILE
    assert profile.confidence == 0.9


def test_document_profiler_falls_back_to_mixed_unknown_for_table_heavy_text() -> None:
    table_text = "\n".join(
        [
            "| Name | Value |",
            "| --- | --- |",
            "| A | 1 |",
            "| B | 2 |",
            "| C | 3 |",
        ]
    )

    profile = DocumentProfiler().profile(
        [
            Document(
                text=f"{table_text}\n\nThis reference page contains structured tabular data.",
                metadata={"page_number": 1, "table_count": 2},
            )
        ]
    )

    assert profile.name == MIXED_UNKNOWN_PROFILE
    assert profile.signals["table_count"] > 3
