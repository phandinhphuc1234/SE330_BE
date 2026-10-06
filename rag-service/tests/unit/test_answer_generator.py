import json

import pytest

from app.core.config import Settings
from app.generation.answer_generator import AnswerGenerator, EbookAnswerRequest
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalHit,
    LibraryVectorRetrievalResponse,
)


class FakeRetrievalService:
    def __init__(self, results):
        self.results = results
        self.requests = []

    async def search(self, request):
        self.requests.append(request)
        return LibraryVectorRetrievalResponse(
            query_text_hash="hash",
            query_text_policy="policy",
            embedding_version="embedding-v1",
            top_k=request.top_k or 6,
            applied_filters={"ebook_id": request.ebook_id},
            results=self.results,
        )


class FakeLLMClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    async def complete_json(self, *, system_prompt, user_prompt):
        self.calls.append((system_prompt, user_prompt))
        return json.dumps(self.response)


def hit(score=0.91):
    return LibraryVectorRetrievalHit(
        point_id="point-1",
        vector_id="vector-1",
        score=score,
        text="Dependency inversion separates high-level and low-level modules.",
        citation={"ebookId": 55, "bookId": 101, "pageStart": 42, "pageEnd": 43},
    )


def settings():
    return Settings(
        llm_model="test-model",
        answer_retrieval_top_k=6,
        answer_score_threshold=0.60,
        answer_max_context_chars=2000,
    )


@pytest.mark.asyncio
async def test_generate_returns_grounded_answer_with_trusted_citation():
    retrieval = FakeRetrievalService([hit()])
    llm = FakeLLMClient(
        {"answer": "Nó tách các module.", "abstained": False, "citationIds": ["vector-1"]}
    )
    generator = AnswerGenerator(retrieval_service=retrieval, llm_client=llm, settings=settings())

    response = await generator.generate(EbookAnswerRequest("DIP là gì?", ebook_id=55))

    assert response.grounded is True
    assert response.abstained is False
    assert response.citations[0]["ebookId"] == 55
    assert response.citations[0]["chunkId"] == "vector-1"
    assert response.citations[0]["pageStart"] == 42
    assert retrieval.requests[0].ebook_id == 55
    assert retrieval.requests[0].score_threshold == 0.60


@pytest.mark.asyncio
async def test_generate_abstains_without_evidence_and_does_not_call_llm():
    retrieval = FakeRetrievalService([hit(score=0.40)])
    llm = FakeLLMClient({})
    generator = AnswerGenerator(retrieval_service=retrieval, llm_client=llm, settings=settings())

    response = await generator.generate(EbookAnswerRequest("Ngoài phạm vi?", ebook_id=55))

    assert response.abstained is True
    assert response.reason == "INSUFFICIENT_EVIDENCE"
    assert response.citations == []
    assert llm.calls == []


@pytest.mark.asyncio
async def test_generate_fails_closed_when_llm_invents_citation():
    retrieval = FakeRetrievalService([hit()])
    llm = FakeLLMClient(
        {"answer": "Câu trả lời đoán.", "abstained": False, "citationIds": ["other-book"]}
    )
    generator = AnswerGenerator(retrieval_service=retrieval, llm_client=llm, settings=settings())

    response = await generator.generate(EbookAnswerRequest("DIP là gì?", ebook_id=55))

    assert response.abstained is True
    assert response.reason == "INVALID_CITATIONS"
    assert response.citations == []


@pytest.mark.asyncio
async def test_generate_abstains_when_retrieval_has_no_page_citation():
    invalid_hit = LibraryVectorRetrievalHit(
        point_id="point-1",
        vector_id="vector-1",
        score=0.91,
        text="Evidence without a page.",
        citation={"ebookId": 55},
    )
    retrieval = FakeRetrievalService([invalid_hit])
    llm = FakeLLMClient({})
    generator = AnswerGenerator(retrieval_service=retrieval, llm_client=llm, settings=settings())

    response = await generator.generate(EbookAnswerRequest("Question", ebook_id=55))

    assert response.abstained is True
    assert response.reason == "INSUFFICIENT_EVIDENCE"
    assert llm.calls == []


@pytest.mark.asyncio
async def test_caller_cannot_lower_configured_safety_threshold():
    retrieval = FakeRetrievalService([])
    llm = FakeLLMClient({})
    generator = AnswerGenerator(retrieval_service=retrieval, llm_client=llm, settings=settings())

    await generator.generate(
        EbookAnswerRequest("Question", ebook_id=55, score_threshold=0.10, top_k=20)
    )

    assert retrieval.requests[0].score_threshold == 0.60
    assert retrieval.requests[0].top_k == 6
