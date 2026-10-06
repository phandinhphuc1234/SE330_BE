import pytest

from app.api.internal.routes_answers import InternalEbookAnswerRequest, answer_ebook_question
from app.generation.answer_generator import EbookAnswerResponse


class FakeAnswerGenerator:
    def __init__(self):
        self.requests = []

    async def generate(self, request):
        self.requests.append(request)
        return EbookAnswerResponse(
            answer="Grounded answer",
            grounded=True,
            abstained=False,
            reason=None,
            citations=[{"ebookId": 55, "chunkId": "vector-1", "pageStart": 4}],
            model="test-model",
        )


@pytest.mark.asyncio
async def test_internal_answer_route_maps_request_and_response():
    generator = FakeAnswerGenerator()
    payload = InternalEbookAnswerRequest.model_validate(
        {"question": " What happened? ", "ebookId": 55, "topK": 4}
    )

    response = await answer_ebook_question(payload, generator=generator)

    assert generator.requests[0].question == "What happened?"
    assert generator.requests[0].ebook_id == 55
    assert response.model_dump(by_alias=True)["promptVersion"] == "library-ebook-answer-v1"
    assert response.citations[0]["ebookId"] == 55


def test_internal_answer_request_rejects_blank_question():
    with pytest.raises(ValueError, match="question must not be blank"):
        InternalEbookAnswerRequest.model_validate({"question": "   ", "ebookId": 55})
