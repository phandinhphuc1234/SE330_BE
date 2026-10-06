from langchain_text_splitters import RecursiveCharacterTextSplitter

from app.core.config import get_settings
from app.ingestion.chunkers.base import BaseChunker, Chunk


class RecursiveChunker(BaseChunker):
    def chunk(self, text: str, metadata: dict | None = None) -> list[Chunk]:
        settings = get_settings()
        splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
        )
        return [Chunk(text=item, metadata=metadata or {}) for item in splitter.split_text(text)]
