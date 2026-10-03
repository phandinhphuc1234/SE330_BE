from llama_index.core import Document

from app.ingestion.chunking import CHAPTER_DETECTION_VERSION, ChapterDetector


def test_chapter_detector_detects_vietnamese_chapter_heading_and_carries_forward() -> None:
    documents = [
        Document(
            text="Lời mở đầu\n\nĐêm đó thành phố yên lặng hơn mọi ngày.",
            metadata={"page_number": 1},
        ),
        Document(
            text="CHƯƠNG I\n\nMinh trở lại thư viện khi cơn mưa vừa dứt.",
            metadata={"page_number": 2},
        ),
        Document(
            text="Cậu bước qua những kệ sách cũ và nghe tiếng gió ngoài hành lang.",
            metadata={"page_number": 3},
        ),
    ]

    chaptered_documents = ChapterDetector().attach_metadata(documents)

    assert chaptered_documents[0].metadata["chapter_detected"] is False
    assert chaptered_documents[0].metadata["chapter_detection_version"] == CHAPTER_DETECTION_VERSION

    assert chaptered_documents[1].metadata["chapter_detected"] is True
    assert chaptered_documents[1].metadata["chapter_detected_on_page"] is True
    assert chaptered_documents[1].metadata["chapter_index"] == 1
    assert chaptered_documents[1].metadata["chapter_number"] == "I"
    assert chaptered_documents[1].metadata["chapter_title"] == "CHƯƠNG I"
    assert chaptered_documents[1].metadata["chapter_source_page"] == 2
    assert chaptered_documents[1].metadata["section_path"] == ["CHƯƠNG I"]

    assert chaptered_documents[2].metadata["chapter_detected"] is True
    assert chaptered_documents[2].metadata["chapter_detected_on_page"] is False
    assert chaptered_documents[2].metadata["chapter_index"] == 1
    assert chaptered_documents[2].metadata["chapter_title"] == "CHƯƠNG I"
    assert chaptered_documents[2].metadata["chapter_source_page"] == 2


def test_chapter_detector_detects_supported_chapter_prefixes() -> None:
    documents = [
        Document(text="Chapter 2\n\nThe corridor opened into a colder room.", metadata={"page_number": 1}),
        Document(text="Phần 3\n\nMột giọng nói vang lên từ cuối phòng.", metadata={"page_number": 2}),
        Document(text="Hồi IV\n\nNgọn đèn cuối cùng vụt tắt.", metadata={"page_number": 3}),
    ]

    chaptered_documents = ChapterDetector().attach_metadata(documents)

    assert chaptered_documents[0].metadata["chapter_title"] == "Chapter 2"
    assert chaptered_documents[0].metadata["chapter_index"] == 1
    assert chaptered_documents[1].metadata["chapter_title"] == "Phần 3"
    assert chaptered_documents[1].metadata["chapter_index"] == 2
    assert chaptered_documents[2].metadata["chapter_title"] == "Hồi IV"
    assert chaptered_documents[2].metadata["chapter_index"] == 3


def test_chapter_detector_detects_more_english_numbered_headings() -> None:
    documents = [
        Document(text="Chap. One: The Library Door\n\nMinh found the key.", metadata={"page_number": 1}),
        Document(text="Part II\n\nThe city became quiet.", metadata={"page_number": 2}),
        Document(text="Book Three\n\nThe rain returned.", metadata={"page_number": 3}),
        Document(text="Vol. IV\n\nA colder morning began.", metadata={"page_number": 4}),
        Document(text="Act 5\n\nThe curtain moved.", metadata={"page_number": 5}),
    ]

    chaptered_documents = ChapterDetector().attach_metadata(documents)

    assert chaptered_documents[0].metadata["chapter_title"] == "Chap. One: The Library Door"
    assert chaptered_documents[0].metadata["chapter_number"] == "One"
    assert chaptered_documents[1].metadata["chapter_title"] == "Part II"
    assert chaptered_documents[1].metadata["chapter_number"] == "II"
    assert chaptered_documents[2].metadata["chapter_title"] == "Book Three"
    assert chaptered_documents[2].metadata["chapter_number"] == "Three"
    assert chaptered_documents[3].metadata["chapter_title"] == "Vol. IV"
    assert chaptered_documents[3].metadata["chapter_number"] == "IV"
    assert chaptered_documents[4].metadata["chapter_title"] == "Act 5"
    assert chaptered_documents[4].metadata["chapter_number"] == "5"


def test_chapter_detector_detects_english_unnumbered_front_and_back_matter() -> None:
    documents = [
        Document(text="Prologue\n\nThe first bell rang.", metadata={"page_number": 1}),
        Document(text="Epilogue: After the Rain\n\nThe town slept.", metadata={"page_number": 2}),
        Document(text="Preface\n\nA note from the author.", metadata={"page_number": 3}),
        Document(text="Introduction\n\nThis book begins with a map.", metadata={"page_number": 4}),
    ]

    chaptered_documents = ChapterDetector().attach_metadata(documents)

    assert chaptered_documents[0].metadata["chapter_title"] == "Prologue"
    assert chaptered_documents[0].metadata["chapter_number"] == "prologue"
    assert chaptered_documents[1].metadata["chapter_title"] == "Epilogue: After the Rain"
    assert chaptered_documents[1].metadata["chapter_number"] == "epilogue"
    assert chaptered_documents[2].metadata["chapter_title"] == "Preface"
    assert chaptered_documents[2].metadata["chapter_number"] == "preface"
    assert chaptered_documents[3].metadata["chapter_title"] == "Introduction"
    assert chaptered_documents[3].metadata["chapter_number"] == "introduction"


def test_chapter_detector_ignores_late_body_mentions() -> None:
    text = "\n".join(
        [
            "Dòng mở đầu của trang.",
            "Đoạn văn thứ hai.",
            "Đoạn văn thứ ba.",
            "Chương 9",
            "Nhưng dòng này nằm quá sâu trong body nên không coi là heading.",
        ]
    )
    documents = [Document(text=text, metadata={"page_number": 1})]

    chaptered_documents = ChapterDetector(max_heading_scan_lines=3).attach_metadata(documents)

    assert chaptered_documents[0].metadata["chapter_detected"] is False
