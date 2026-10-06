from app.indexing.base import VectorChunk, VectorStore
from app.indexing.embedding_provider import EmbeddingProvider


class Indexer:
    def __init__(self, embedding_provider: EmbeddingProvider, vector_store: VectorStore) -> None:
        self.embedding_provider = embedding_provider
        self.vector_store = vector_store

    async def index(self, chunks: list[VectorChunk]) -> None:
        await self.vector_store.upsert(chunks)
