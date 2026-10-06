from collections.abc import Mapping, Sequence

from llama_index.core import Document
from llama_index.core.schema import BaseNode

from app.ingestion.llama_index import LLAMAINDEX_SENTENCE_CHUNKER, documents_to_sentence_nodes

NARRATIVE_CHUNKING_STRATEGY = "library_pdf_narrative"
NARRATIVE_CHUNKING_STRATEGY_VERSION = "v1"
LLAMA_SENTENCE_CHUNKING_STRATEGY = "llamaindex_sentence_splitter"


class LlamaSentenceChunkingStrategy:
    """Narrative MVP chunking strategy backed by LlamaIndex SentenceSplitter."""

    name = NARRATIVE_CHUNKING_STRATEGY
    version = NARRATIVE_CHUNKING_STRATEGY_VERSION
    chunker_name = LLAMAINDEX_SENTENCE_CHUNKER

    def build_nodes(
        self,
        documents: Sequence[Document],
        *,
        chunk_size: int,
        chunk_overlap: int,
    ) -> list[BaseNode]:
        """Attach strategy metadata and delegate splitting to LlamaIndex."""

        prepared_documents = [
            _copy_document_with_metadata(
                document,
                {
                    "chunking_strategy": self.name,
                    "chunking_strategy_version": self.version,
                    "chunk_level": "child",
                },
            )
            for document in documents
        ]
        return documents_to_sentence_nodes(
            prepared_documents,
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            chunker_name=self.chunker_name,
        )


def _copy_document_with_metadata(document: Document, extra_metadata: Mapping) -> Document:
    """Copy a LlamaIndex Document while adding strategy metadata.

    Avoid mutating the cleaner output documents because they may later be used
    for artifacts/debug reports.
    """

    return Document(
        text=document.text or "",
        metadata={**dict(document.metadata or {}), **dict(extra_metadata)},
        id_=document.id_,
    )
