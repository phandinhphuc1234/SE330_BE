"""Compatibility adapter; all dense retrieval uses the scoped live service."""

from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalRequest, LibraryVectorRetrievalService


class VectorRetriever:
    def __init__(self, *, service=None) -> None:
        self.service = service or LibraryVectorRetrievalService()

    async def search(self, query: str, top_k: int, **scope):
        return await self.service.search(LibraryVectorRetrievalRequest(query=query, top_k=top_k, **scope))
