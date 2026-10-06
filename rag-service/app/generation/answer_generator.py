"""Grounded answer orchestration for one authorized Library ebook."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError as PydanticValidationError

from app.core.config import Settings, get_settings
from app.core.exceptions import LLMError
from app.generation.citation_builder import build_citations, source_id_for
from app.generation.llm_client import ConfiguredLLMClient, LLMClient
from app.generation.prompt_builder import PROMPT_VERSION, PromptEvidence, build_answer_prompt
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalRequest,
    LibraryVectorRetrievalService,
)
from app.retrieval.retrieval_pipeline import RetrievalPipeline


ABSTENTION_MESSAGE = "Không đủ bằng chứng trong ebook để trả lời câu hỏi này."


class _LLMAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer: str = Field(max_length=20_000)
    abstained: bool
    citation_ids: list[str] = Field(alias="citationIds", max_length=20)


@dataclass(frozen=True)
class EbookAnswerRequest:
    question: str
    ebook_id: int
    top_k: int | None = None
    score_threshold: float | None = None
    retrieval_mode: str | None = None


@dataclass(frozen=True)
class EbookAnswerResponse:
    answer: str
    grounded: bool
    abstained: bool
    reason: str | None
    citations: list[dict[str, Any]] = field(default_factory=list)
    model: str | None = None
    prompt_version: str = PROMPT_VERSION


class AnswerGenerator:
    def __init__(
        self,
        *,
        retrieval_service: LibraryVectorRetrievalService | RetrievalPipeline | None = None,
        llm_client: LLMClient | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.retrieval_service = retrieval_service or RetrievalPipeline(settings=self.settings)
        self.llm_client = llm_client or ConfiguredLLMClient(settings=self.settings)

    async def generate(self, request: EbookAnswerRequest) -> EbookAnswerResponse:
        threshold = max(
            request.score_threshold or self.settings.answer_score_threshold,
            self.settings.answer_score_threshold,
        )
        top_k = min(request.top_k or self.settings.answer_retrieval_top_k, self.settings.answer_retrieval_top_k)
        retrieval = await self.retrieval_service.search(
            LibraryVectorRetrievalRequest(
                query=request.question.strip(),
                ebook_id=request.ebook_id,
                top_k=top_k,
                score_threshold=threshold,
                expand_context=True,
                max_context_chars=self.settings.answer_max_context_chars,
                retrieval_mode=request.retrieval_mode,
            )
        )
        eligible = [
            hit
            for hit in retrieval.results
            if hit.score >= threshold and self._has_valid_ebook_citation(hit.citation, request.ebook_id)
        ]
        if not eligible:
            return self._abstain("INSUFFICIENT_EVIDENCE")

        evidence_by_id = {source_id_for(hit): hit for hit in eligible}
        prompt = build_answer_prompt(
            request.question,
            [
                PromptEvidence(source_id_for(hit), hit.context_text or hit.text)
                for hit in eligible
            ],
            max_context_chars=self.settings.answer_max_context_chars,
        )
        included = set(prompt.included_source_ids)
        evidence_by_id = {key: value for key, value in evidence_by_id.items() if key in included}
        if not evidence_by_id:
            return self._abstain("INSUFFICIENT_EVIDENCE")

        raw_answer = await self.llm_client.complete_json(
            system_prompt=prompt.system_prompt,
            user_prompt=prompt.user_prompt,
        )
        try:
            draft = _LLMAnswer.model_validate(json.loads(raw_answer))
        except (json.JSONDecodeError, PydanticValidationError) as error:
            raise LLMError("LLM returned invalid structured output", error_code="LLM_INVALID_RESPONSE") from error

        if draft.abstained:
            return self._abstain("MODEL_ABSTAINED")
        if not draft.answer.strip() or not draft.citation_ids:
            return self._abstain("INVALID_CITATIONS")

        try:
            citations = build_citations(draft.citation_ids, evidence_by_id)
        except ValueError:
            return self._abstain("INVALID_CITATIONS")
        if not citations:
            return self._abstain("INVALID_CITATIONS")

        return EbookAnswerResponse(
            answer=draft.answer.strip(),
            grounded=True,
            abstained=False,
            reason=None,
            citations=citations,
            model=self.settings.llm_model,
        )

    def _abstain(self, reason: str) -> EbookAnswerResponse:
        return EbookAnswerResponse(
            answer=ABSTENTION_MESSAGE,
            grounded=False,
            abstained=True,
            reason=reason,
            citations=[],
            model=None,
        )

    @staticmethod
    def _has_valid_ebook_citation(citation: dict[str, Any], ebook_id: int) -> bool:
        page_start = citation.get("pageStart")
        return citation.get("ebookId") == ebook_id and isinstance(page_start, int) and page_start > 0
