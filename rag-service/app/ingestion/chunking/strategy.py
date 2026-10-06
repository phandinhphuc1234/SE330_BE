from collections.abc import Sequence
from typing import Protocol

from llama_index.core import Document
from llama_index.core.schema import BaseNode


class ChunkingStrategy(Protocol):
    """Boundary between ingestion pipeline and concrete chunking strategy."""

    name: str
    version: str
    chunker_name: str

    def build_nodes(
        self,
        documents: Sequence[Document],
        *,
        chunk_size: int,
        chunk_overlap: int,
    ) -> list[BaseNode]:
        """Split LlamaIndex Documents into metadata-enriched Nodes."""
        ...
