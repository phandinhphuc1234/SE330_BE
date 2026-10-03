from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass
class VectorChunk:
    id: str
    text: str
    vector: list[float]
    metadata: dict = field(default_factory=dict)


@dataclass
class VectorSearchQuery:
    """Provider-agnostic vector search request.

    Search must carry filters as part of the query object so retrieval services
    can enforce `active`, embedding version and Library permission scope before
    the request reaches Qdrant.
    """

    query_vector: list[float]
    top_k: int
    filters: dict[str, Any] = field(default_factory=dict)
    score_threshold: float | None = None
    include_vectors: bool = False

    def __post_init__(self) -> None:
        if not self.query_vector:
            raise ValueError("VectorSearchQuery.query_vector must not be empty.")
        if self.top_k <= 0:
            raise ValueError("VectorSearchQuery.top_k must be greater than zero.")


@dataclass
class SearchResult:
    id: str
    score: float
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)
    vector_id: str | None = None
    retrieval_source: str = "vector"


class VectorStore(ABC):
    @abstractmethod
    async def upsert(self, chunks: list[VectorChunk]) -> None:
        raise NotImplementedError

    @abstractmethod
    async def search(self, query: VectorSearchQuery) -> list[SearchResult]:
        raise NotImplementedError

    @abstractmethod
    async def delete(self, chunk_ids: list[str]) -> None:
        raise NotImplementedError
