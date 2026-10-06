from app.ingestion.chunkers.base import BaseChunker, Chunk


class SemanticChunker(BaseChunker):
    def chunk(self, text: str, metadata: dict | None = None) -> list[Chunk]:
        raise NotImplementedError
