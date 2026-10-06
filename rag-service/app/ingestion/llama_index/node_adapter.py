import hashlib
from collections.abc import Sequence
from typing import Any

from llama_index.core import Document
from llama_index.core.node_parser import SentenceSplitter
from llama_index.core.schema import BaseNode, MetadataMode

from app.core.config import get_settings
from app.ingestion.chunkers.base import Chunk

LLAMAINDEX_SENTENCE_CHUNKER = "llamaindex_sentence_splitter"


def build_sentence_splitter(
    *,
    chunk_size: int | None = None,
    chunk_overlap: int | None = None,
    include_metadata: bool = False,
    include_prev_next_rel: bool = True,
) -> SentenceSplitter:
    """Build the default LlamaIndex sentence-aware node parser.

    This is the B2 boundary: the project delegates text splitting to
    LlamaIndex, while keeping metadata enrichment and persistence mapping in
    local adapter code.
    """

    settings = get_settings()
    effective_chunk_size = chunk_size or settings.chunk_size
    effective_chunk_overlap = chunk_overlap if chunk_overlap is not None else settings.chunk_overlap
    return SentenceSplitter(
        chunk_size=effective_chunk_size,
        chunk_overlap=effective_chunk_overlap,
        include_metadata=include_metadata,
        include_prev_next_rel=include_prev_next_rel,
    )


def documents_to_sentence_nodes(
    documents: Sequence[Document],
    *,
    chunk_size: int | None = None,
    chunk_overlap: int | None = None,
    chunker_name: str = LLAMAINDEX_SENTENCE_CHUNKER,
) -> list[BaseNode]:
    """Split cleaned page-level Documents into LlamaIndex Nodes.

    Each cleaned page Document can produce one or more nodes. Metadata from the
    Document is preserved by LlamaIndex and then normalized for the existing RAG
    chunk contract: pageStart/pageEnd, chunkIndex, chunk_hash, and vector_id.
    """

    # Cleaner metadata is intentionally verbose for audit/debug. If LlamaIndex
    # includes that metadata while calculating split size, small chunks can fail
    # before splitting. Split by text first, then copy Document metadata onto
    # the produced nodes ourselves.
    splitter = build_sentence_splitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        include_metadata=False,
    )
    nodes = _split_documents_and_restore_metadata(splitter, documents)
    return enrich_sentence_nodes(
        nodes,
        chunk_size=splitter.chunk_size,
        chunk_overlap=splitter.chunk_overlap,
        chunker_name=chunker_name,
    )


def enrich_sentence_nodes(
    nodes: Sequence[BaseNode],
    *,
    chunk_size: int,
    chunk_overlap: int,
    chunker_name: str = LLAMAINDEX_SENTENCE_CHUNKER,
) -> list[BaseNode]:
    """Add stable RAG chunk metadata to LlamaIndex nodes."""

    enriched_nodes = list(nodes)
    for chunk_index, node in enumerate(enriched_nodes):
        text = _node_text(node)
        text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
        metadata = dict(node.metadata or {})
        _copy_if_missing(metadata, "book_id", "bookId")
        _copy_if_missing(metadata, "ebook_id", "ebookId")
        _copy_if_missing(metadata, "external_document_id", "documentId")
        _copy_if_missing(metadata, "source_type", "sourceType")

        page_number = _resolve_page_number(metadata)
        if page_number is not None:
            metadata.setdefault("page_number", page_number)
            metadata.setdefault("pageStart", page_number)
            metadata.setdefault("pageEnd", page_number)

        metadata.update(
            {
                "node_id": node.id_,
                "chunk_index": chunk_index,
                "chunkIndex": chunk_index,
                "chunk_hash": text_hash,
                "chunker": chunker_name,
                "chunk_size": chunk_size,
                "chunk_overlap": chunk_overlap,
                "embedding_status": "pending",
            }
        )
        document_id = metadata.get("document_id")
        if document_id is not None:
            metadata["vector_id"] = f"doc-{document_id}-chunk-{chunk_index}-{text_hash[:12]}"

        node.metadata = metadata

    return enriched_nodes


def llama_nodes_to_chunks(nodes: Sequence[BaseNode]) -> list[Chunk]:
    """Map LlamaIndex Nodes back to the internal Chunk dataclass.

    The current persistence layer still expects `app.ingestion.chunkers.base.Chunk`.
    This adapter lets B2 be tested without replacing the database pipeline yet.
    """

    chunks: list[Chunk] = []
    for node in nodes:
        text = _node_text(node).strip()
        if not text:
            continue
        chunks.append(Chunk(text=text, metadata=dict(node.metadata or {})))
    return chunks


def _split_documents_and_restore_metadata(
    splitter: SentenceSplitter,
    documents: Sequence[Document],
) -> list[BaseNode]:
    """Split each Document and copy its metadata to every produced node."""

    nodes: list[BaseNode] = []
    for document in documents:
        split_document = Document(
            text=document.text,
            metadata={},
            id_=document.id_,
        )
        document_nodes = splitter.get_nodes_from_documents([split_document])
        document_metadata = dict(document.metadata or {})
        for node in document_nodes:
            node.metadata = {**document_metadata, **dict(node.metadata or {})}
        nodes.extend(document_nodes)
    return nodes


def _node_text(node: BaseNode) -> str:
    """Read node text without metadata prefixes."""

    return node.get_content(metadata_mode=MetadataMode.NONE)


def _resolve_page_number(metadata: dict[str, Any]) -> int | None:
    """Resolve page number from snake/camel metadata fields."""

    page_number = metadata.get("page_number")
    if isinstance(page_number, int) and page_number > 0:
        return page_number
    page_start = metadata.get("pageStart")
    if isinstance(page_start, int) and page_start > 0:
        return page_start
    return None


def _copy_if_missing(metadata: dict[str, Any], source_key: str, target_key: str) -> None:
    """Copy a metadata field when the target naming style is missing."""

    if target_key not in metadata and source_key in metadata:
        metadata[target_key] = metadata[source_key]
