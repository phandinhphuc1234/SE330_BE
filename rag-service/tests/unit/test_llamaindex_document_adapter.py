from llama_index.core import Document

from app.ingestion.llama_index import (
    parsed_document_to_llama_document,
    parsed_documents_to_llama_documents,
)
from app.ingestion.parsers.base import ParsedDocument


def test_parsed_document_to_llama_document_preserves_raw_text_and_metadata() -> None:
    parsed_document = ParsedDocument(
        text="# Intro\n\nRaw parsed page text.",
        metadata={
            "page_number": 3,
            "parser": "pymupdf4llm",
            "table_count": 1,
        },
    )

    document = parsed_document_to_llama_document(
        parsed_document,
        base_metadata={
            "document_id": 10,
            "book_id": 101,
            "ebook_id": 55,
        },
    )

    assert isinstance(document, Document)
    assert document.text == "# Intro\n\nRaw parsed page text."
    assert document.metadata["document_id"] == 10
    assert document.metadata["book_id"] == 101
    assert document.metadata["ebook_id"] == 55
    assert document.metadata["page_number"] == 3
    assert document.metadata["parser"] == "pymupdf4llm"
    assert document.metadata["source_parser"] == "pymupdf4llm"
    assert document.metadata["table_count"] == 1
    assert document.metadata["ingestion_adapter"] == "llama_index_document_adapter"


def test_parsed_documents_to_llama_documents_keeps_one_document_per_page() -> None:
    parsed_documents = [
        ParsedDocument(text="Page one", metadata={"page_number": 1}),
        ParsedDocument(text="Page two", metadata={"page_number": 2}),
    ]

    documents = parsed_documents_to_llama_documents(
        parsed_documents,
        base_metadata={"document_id": 10},
    )

    assert [document.text for document in documents] == ["Page one", "Page two"]
    assert [document.metadata["page_number"] for document in documents] == [1, 2]
    assert [document.metadata["document_id"] for document in documents] == [10, 10]


def test_parsed_documents_to_llama_documents_adds_fallback_page_number() -> None:
    parsed_documents = [
        ParsedDocument(text="Missing page metadata", metadata={}),
        ParsedDocument(text="Explicit page metadata", metadata={"page_number": 9}),
    ]

    documents = parsed_documents_to_llama_documents(parsed_documents)

    assert documents[0].metadata["page_number"] == 1
    assert documents[1].metadata["page_number"] == 9


def test_parsed_document_to_llama_document_does_not_mutate_input_metadata() -> None:
    metadata = {"page_number": 1}
    parsed_document = ParsedDocument(text="Page one", metadata=metadata)

    document = parsed_document_to_llama_document(
        parsed_document,
        base_metadata={"document_id": 10},
    )
    document.metadata["page_number"] = 99

    assert metadata["page_number"] == 1
    assert parsed_document.metadata["page_number"] == 1
