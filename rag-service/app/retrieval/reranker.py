class Reranker:
    async def rerank(self, query: str, results: list, top_k: int):
        return results[:top_k]
