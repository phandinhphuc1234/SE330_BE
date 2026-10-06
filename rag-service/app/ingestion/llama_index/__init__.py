"""LlamaIndex integration helpers for the ingestion pipeline.

This package is intentionally an adapter layer. It lets the project use
LlamaIndex's Document/Node abstractions without forcing the whole ingestion
pipeline to depend directly on LlamaIndex internals.
"""

from app.ingestion.llama_index.document_adapter import (
    parsed_document_to_llama_document,
    parsed_documents_to_llama_documents,
)
from app.ingestion.llama_index.node_adapter import (
    LLAMAINDEX_SENTENCE_CHUNKER,
    build_sentence_splitter,
    documents_to_sentence_nodes,
    llama_nodes_to_chunks,
)
from app.ingestion.llama_index.pdf_cleaning_transformation import PdfCleaningTransformation

__all__ = [
    "PdfCleaningTransformation",
    "LLAMAINDEX_SENTENCE_CHUNKER",
    "build_sentence_splitter",
    "documents_to_sentence_nodes",
    "llama_nodes_to_chunks",
    "parsed_document_to_llama_document",
    "parsed_documents_to_llama_documents",
]
