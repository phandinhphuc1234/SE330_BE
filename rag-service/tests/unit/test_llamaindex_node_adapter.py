from llama_index.core import Document

from app.ingestion.chunkers.base import Chunk
from app.ingestion.llama_index import (
    PdfCleaningTransformation,
    documents_to_sentence_nodes,
    llama_nodes_to_chunks,
)


def test_documents_to_sentence_nodes_preserves_page_metadata_and_adds_chunk_fields() -> None:
    document = Document(
        text=(
            "Retrieval augmented generation helps the library answer questions. "
            "It searches relevant book chunks before generating an answer. "
            "The system must preserve page citations for every response. "
        )
        * 8,
        metadata={
            "document_id": 7,
            "external_document_id": "doc_ebook_55",
            "book_id": 101,
            "ebook_id": 55,
            "source_type": "LIBRARY_EBOOK",
            "page_number": 12,
            "cleaning_version": "pdf-clean-v1.0.0",
        },
        id_="doc-page-12",
    )

    nodes = documents_to_sentence_nodes(
        [document],
        chunk_size=96,
        chunk_overlap=16,
    )

    assert len(nodes) >= 2
    first_node = nodes[0]
    first_text = first_node.get_text()
    assert "Retrieval augmented generation" in first_text
    assert "document_id: 7" not in first_text
    assert first_node.metadata["document_id"] == 7
    assert first_node.metadata["documentId"] == "doc_ebook_55"
    assert first_node.metadata["bookId"] == 101
    assert first_node.metadata["ebookId"] == 55
    assert first_node.metadata["sourceType"] == "LIBRARY_EBOOK"
    assert first_node.metadata["page_number"] == 12
    assert first_node.metadata["pageStart"] == 12
    assert first_node.metadata["pageEnd"] == 12
    assert first_node.metadata["cleaning_version"] == "pdf-clean-v1.0.0"
    assert first_node.metadata["chunker"] == "llamaindex_sentence_splitter"
    assert first_node.metadata["chunk_size"] == 96
    assert first_node.metadata["chunk_overlap"] == 16
    assert first_node.metadata["chunk_index"] == 0
    assert first_node.metadata["chunkIndex"] == 0
    assert len(first_node.metadata["chunk_hash"]) == 64
    assert first_node.metadata["vector_id"].startswith("doc-7-chunk-0-")
    assert first_node.metadata["embedding_status"] == "pending"


def test_sentence_nodes_keep_page_boundaries_for_multiple_documents() -> None:
    documents = [
        Document(
            text="Page one explains upload validation.",
            metadata={"document_id": 7, "page_number": 1},
            id_="page-1",
        ),
        Document(
            text="Page two explains chunk metadata.",
            metadata={"document_id": 7, "page_number": 2},
            id_="page-2",
        ),
    ]

    nodes = documents_to_sentence_nodes(
        documents,
        chunk_size=128,
        chunk_overlap=16,
    )

    assert [node.metadata["page_number"] for node in nodes] == [1, 2]
    assert [node.metadata["pageStart"] for node in nodes] == [1, 2]
    assert [node.metadata["chunk_index"] for node in nodes] == [0, 1]


def test_llama_nodes_to_chunks_maps_nodes_to_internal_chunk_dataclass() -> None:
    document = Document(
        text="A cleaned page can become an internal chunk.",
        metadata={"document_id": 7, "page_number": 3},
        id_="page-3",
    )
    nodes = documents_to_sentence_nodes(
        [document],
        chunk_size=128,
        chunk_overlap=16,
    )

    chunks = llama_nodes_to_chunks(nodes)

    assert len(chunks) == 1
    assert isinstance(chunks[0], Chunk)
    assert chunks[0].text == "A cleaned page can become an internal chunk."
    assert chunks[0].metadata["page_number"] == 3
    assert chunks[0].metadata["vector_id"].startswith("doc-7-chunk-0-")


def test_b0_b1_b2_chain_cleans_then_splits_documents() -> None:
    raw_document = Document(
        text="Page 4\n\nThe efﬁcient   workﬂow keeps metadata.\n4",
        metadata={
            "document_id": 7,
            "external_document_id": "doc_ebook_55",
            "page_number": 4,
        },
        id_="page-4",
    )

    cleaned_documents = PdfCleaningTransformation(max_blank_lines=1)([raw_document])
    nodes = documents_to_sentence_nodes(
        cleaned_documents,
        chunk_size=128,
        chunk_overlap=16,
    )

    assert len(nodes) == 1
    assert nodes[0].get_text() == "The efficient workflow keeps metadata."
    assert nodes[0].metadata["page_number"] == 4
    assert nodes[0].metadata["documentId"] == "doc_ebook_55"
    assert nodes[0].metadata["cleaning_version"] == "pdf-clean-v1.0.0"
    assert nodes[0].metadata["cleaning_quality_status"] == "GOOD"
    assert nodes[0].metadata["chunker"] == "llamaindex_sentence_splitter"
