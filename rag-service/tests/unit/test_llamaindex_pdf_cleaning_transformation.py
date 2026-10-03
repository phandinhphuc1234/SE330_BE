from llama_index.core import Document
from llama_index.core.ingestion.pipeline import run_transformations

from app.ingestion.llama_index import PdfCleaningTransformation


def test_pdf_cleaning_transformation_cleans_llamaindex_documents() -> None:
    document = Document(
        text="Page 1\n\nThe efﬁcient   workﬂow\u00a0keeps text.\n1",
        metadata={
            "page_number": 1,
            "document_id": 10,
            "parser": "pymupdf4llm",
        },
        id_="page-1",
    )

    transformation = PdfCleaningTransformation(max_blank_lines=1)

    cleaned_documents = transformation([document])

    assert len(cleaned_documents) == 1
    cleaned_document = cleaned_documents[0]
    assert isinstance(cleaned_document, Document)
    assert cleaned_document.id_ == "page-1"
    assert cleaned_document.text == "The efficient workflow keeps text."
    assert cleaned_document.metadata["page_number"] == 1
    assert cleaned_document.metadata["document_id"] == 10
    assert cleaned_document.metadata["parser"] == "pymupdf4llm"
    assert cleaned_document.metadata["cleaning_version"] == "pdf-clean-v1.0.0"
    assert cleaned_document.metadata["cleaning_quality_status"] == "GOOD"
    assert cleaned_document.metadata["cleaning_can_chunk"] is True
    assert cleaned_document.metadata["llama_index_transformation"] == "PdfCleaningTransformation"


def test_pdf_cleaning_transformation_is_compatible_with_llamaindex_runner() -> None:
    documents = [
        Document(text="Library System\nBody one\nFooter Text", metadata={"page_number": 1}, id_="page-1"),
        Document(text="Library System\nBody two\nFooter Text", metadata={"page_number": 2}, id_="page-2"),
        Document(text="Library System\nBody three\nFooter Text", metadata={"page_number": 3}, id_="page-3"),
    ]

    cleaned_documents = run_transformations(
        documents,
        [
            PdfCleaningTransformation(
                header_footer_top_lines=1,
                header_footer_bottom_lines=1,
                header_footer_min_repeat_ratio=0.6,
                header_footer_removal_enabled=True,
                fix_line_breaks_enabled=False,
            )
        ],
    )

    assert [document.text for document in cleaned_documents] == ["Body one", "Body two", "Body three"]
    assert cleaned_documents[0].metadata["removed_header_footer_count"] == 2
    assert cleaned_documents[0].metadata["removed_header_footer_lines"] == [
        "Library System",
        "Footer Text",
    ]


def test_pdf_cleaning_transformation_preserves_empty_page_as_failed_metadata() -> None:
    document = Document(text="   ", metadata={"page_number": 1}, id_="empty-page")

    cleaned_documents = PdfCleaningTransformation()([document])

    assert len(cleaned_documents) == 1
    assert cleaned_documents[0].id_ == "empty-page"
    assert cleaned_documents[0].text == ""
    assert cleaned_documents[0].metadata["cleaning_quality_status"] == "FAILED"
    assert cleaned_documents[0].metadata["cleaning_can_chunk"] is False
    assert cleaned_documents[0].metadata["cleaning_warnings"] == ["EMPTY_PAGE_TEXT"]
