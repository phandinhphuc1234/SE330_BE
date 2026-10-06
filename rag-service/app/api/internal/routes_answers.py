"""Internal grounded-answer API called only after Spring authorizes ebook access."""

from functools import lru_cache
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.core.exceptions import LLMError, ServiceUnavailableError, ValidationError
from app.generation.answer_generator import AnswerGenerator, EbookAnswerRequest, EbookAnswerResponse


router = APIRouter()


class InternalEbookAnswerRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    question: str = Field(min_length=1, max_length=4096)
    ebook_id: Annotated[int, Field(gt=0)] = Field(alias="ebookId")
    top_k: Annotated[int, Field(gt=0, le=20)] | None = Field(default=None, alias="topK")
    score_threshold: Annotated[float, Field(ge=0.0, le=1.0)] | None = Field(
        default=None,
        alias="scoreThreshold",
    )
    retrieval_mode: Literal["dense", "hybrid", "graph"] | None = Field(
        default=None,
        alias="retrievalMode",
    )

    @field_validator("question")
    @classmethod
    def normalize_question(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("question must not be blank")
        return normalized


class InternalEbookAnswerResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    answer: str
    grounded: bool
    abstained: bool
    reason: str | None = None
    citations: list[dict[str, Any]]
    model: str | None = None
    prompt_version: str = Field(alias="promptVersion")


@lru_cache(maxsize=1)
def get_answer_generator() -> AnswerGenerator:
    return AnswerGenerator()


@router.post("", response_model=InternalEbookAnswerResponse)
async def answer_ebook_question(
    payload: InternalEbookAnswerRequest,
    generator: AnswerGenerator = Depends(get_answer_generator),
) -> InternalEbookAnswerResponse:
    try:
        response = await generator.generate(
            EbookAnswerRequest(
                question=payload.question,
                ebook_id=payload.ebook_id,
                top_k=payload.top_k,
                score_threshold=payload.score_threshold,
                retrieval_mode=payload.retrieval_mode,
            )
        )
    except (LLMError, ServiceUnavailableError):
        raise
    except ValueError as error:
        raise ValidationError(str(error), error_code="INVALID_ANSWER_REQUEST") from error
    except Exception as error:  # noqa: BLE001 - provider/vector DB SDK errors vary.
        raise ServiceUnavailableError(
            "Grounded answer generation failed",
            error_code="ANSWER_GENERATION_FAILED",
        ) from error
    return _to_api_response(response)


def _to_api_response(response: EbookAnswerResponse) -> InternalEbookAnswerResponse:
    return InternalEbookAnswerResponse(
        answer=response.answer,
        grounded=response.grounded,
        abstained=response.abstained,
        reason=response.reason,
        citations=response.citations,
        model=response.model,
        promptVersion=response.prompt_version,
    )
