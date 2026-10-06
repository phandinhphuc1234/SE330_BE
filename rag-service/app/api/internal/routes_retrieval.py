"""Internal retrieval API used by the Library service.

This route is not a public chatbot endpoint. Library authorizes the user first,
then calls RAG with a trusted book/ebook scope so RAG can search Qdrant with
mandatory metadata filters.
"""

from functools import lru_cache
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.core.exceptions import ServiceUnavailableError, ValidationError
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalRequest,
    LibraryVectorRetrievalResponse as ServiceRetrievalResponse,
    LibraryVectorRetrievalService,
)
from app.retrieval.retrieval_pipeline import RetrievalPipeline


router = APIRouter()


class InternalVectorSearchRequest(BaseModel):
    """Request body for internal Library vector search."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    query: str = Field(min_length=1, max_length=4096)
    book_id: Annotated[int, Field(gt=0)] | None = Field(default=None, alias="bookId")
    ebook_id: Annotated[int, Field(gt=0)] | None = Field(default=None, alias="ebookId")
    document_id: Annotated[int, Field(gt=0)] | None = Field(default=None, alias="documentId")
    top_k: Annotated[int, Field(gt=0, le=50)] | None = Field(default=None, alias="topK")
    score_threshold: Annotated[float, Field(ge=0.0, le=1.0)] | None = Field(
        default=None,
        alias="scoreThreshold",
    )
    retrieval_mode: Literal["dense", "hybrid", "graph"] | None = Field(
        default=None,
        alias="retrievalMode",
    )

    @model_validator(mode="after")
    def require_library_scope(self) -> "InternalVectorSearchRequest":
        if self.book_id is None and self.ebook_id is None and self.document_id is None:
            raise ValueError("bookId, ebookId, or documentId is required.")
        return self


class InternalVectorSearchHit(BaseModel):
    """One retrieved chunk returned to Library."""

    model_config = ConfigDict(populate_by_name=True)

    point_id: str = Field(alias="pointId")
    vector_id: str | None = Field(default=None, alias="vectorId")
    score: float
    text: str
    citation: dict[str, Any] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)


class InternalVectorSearchResponse(BaseModel):
    """Internal response containing evidence chunks, not a generated answer."""

    model_config = ConfigDict(populate_by_name=True)

    query_text_hash: str = Field(alias="queryTextHash")
    query_text_policy: str = Field(alias="queryTextPolicy")
    embedding_version: str = Field(alias="embeddingVersion")
    top_k: int = Field(alias="topK")
    result_count: int = Field(alias="resultCount")
    applied_filters: dict[str, Any] = Field(alias="appliedFilters")
    results: list[InternalVectorSearchHit]


@lru_cache(maxsize=1)
def get_library_vector_retrieval_service() -> RetrievalPipeline:
    """Build the production hybrid retrieval pipeline for dependency injection."""

    return RetrievalPipeline()


@router.post("/search", response_model=InternalVectorSearchResponse)
async def search_library_vectors(
    payload: InternalVectorSearchRequest,
    service: LibraryVectorRetrievalService | RetrievalPipeline = Depends(
        get_library_vector_retrieval_service
    ),
) -> InternalVectorSearchResponse:
    """Search indexed Library ebook chunks using Gemini query embedding + Qdrant."""

    try:
        response = await service.search(
            LibraryVectorRetrievalRequest(
                query=payload.query,
                book_id=payload.book_id,
                ebook_id=payload.ebook_id,
                document_id=payload.document_id,
                top_k=payload.top_k,
                score_threshold=payload.score_threshold,
                retrieval_mode=payload.retrieval_mode,
            )
        )
    except ValueError as error:
        raise ValidationError(str(error), error_code="INVALID_RETRIEVAL_REQUEST") from error
    except Exception as error:  # noqa: BLE001 - provider/vector DB SDK errors vary.
        raise ServiceUnavailableError(
            "Vector retrieval failed",
            error_code="VECTOR_RETRIEVAL_FAILED",
        ) from error

    return _to_api_response(response)


def _to_api_response(response: ServiceRetrievalResponse) -> InternalVectorSearchResponse:
    return InternalVectorSearchResponse(
        queryTextHash=response.query_text_hash,
        queryTextPolicy=response.query_text_policy,
        embeddingVersion=response.embedding_version,
        topK=response.top_k,
        resultCount=response.result_count,
        appliedFilters=response.applied_filters,
        results=[
            InternalVectorSearchHit(
                pointId=hit.point_id,
                vectorId=hit.vector_id,
                score=hit.score,
                text=hit.text,
                citation=hit.citation,
                metadata=hit.metadata,
            )
            for hit in response.results
        ],
    )
