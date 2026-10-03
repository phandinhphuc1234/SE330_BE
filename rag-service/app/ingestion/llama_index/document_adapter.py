from collections.abc import Mapping, Sequence

from llama_index.core import Document

from app.ingestion.parsers.base import ParsedDocument


def parsed_document_to_llama_document(
    parsed_document: ParsedDocument,
    *,
    base_metadata: Mapping | None = None,
    fallback_page_number: int | None = None,
) -> Document:
    """Convert one parser output unit into a LlamaIndex Document.

    The parser remains responsible for extracting text from the source file.
    This adapter only wraps that already-parsed text into LlamaIndex's standard
    container so later stages can use LlamaIndex transformations and node
    parsers without losing page/book metadata.
    """

    metadata = _build_document_metadata(
        parsed_document,
        base_metadata=base_metadata,
        fallback_page_number=fallback_page_number,
    )
    return Document(
        text=parsed_document.text or "",
        metadata=metadata,
    )


def parsed_documents_to_llama_documents(
    parsed_documents: Sequence[ParsedDocument],
    *,
    base_metadata: Mapping | None = None,
) -> list[Document]:
    """Convert parser output into page-level LlamaIndex Documents.

    For PDFs, the current parser returns one ParsedDocument per page. This
    function preserves that shape: one parsed page becomes one LlamaIndex
    Document, and a later NodeParser can split each Document into one or more
    Nodes/chunks.
    """

    return [
        parsed_document_to_llama_document(
            parsed_document,
            base_metadata=base_metadata,
            fallback_page_number=index,
        )
        for index, parsed_document in enumerate(parsed_documents, start=1)
    ]


def _build_document_metadata(
    parsed_document: ParsedDocument,
    *,
    base_metadata: Mapping | None,
    fallback_page_number: int | None,
) -> dict:
    """Merge document-level metadata with parser metadata safely.

    Document-level metadata normally contains Library/RAG identifiers such as
    document_id, book_id, and ebook_id. Parser metadata contains page-specific
    fields such as page_number and parser name. Parser metadata wins because it
    is more specific to the current page.
    """

    metadata = {
        **dict(base_metadata or {}),
        **dict(parsed_document.metadata or {}),
    }
    if fallback_page_number is not None:
        metadata.setdefault("page_number", fallback_page_number)
    if "parser" in parsed_document.metadata:
        metadata.setdefault("source_parser", parsed_document.metadata["parser"])
    metadata.setdefault("ingestion_adapter", "llama_index_document_adapter")
    return metadata
