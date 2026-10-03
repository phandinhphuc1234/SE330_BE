from types import SimpleNamespace

import pytest

from app.core.config import Settings
from app.indexing.providers.gemini_embedding_provider import (
    GeminiEmbeddingProvider,
    GeminiEmbeddingProviderError,
    extract_embedding_values,
    extract_first_embedding_values,
)


def _settings(
    *,
    model: str = "gemini-embedding-2",
    dimension: int = 3,
    batch_size: int = 2,
) -> Settings:
    settings = Settings(_env_file=None)
    settings.embedding_model = model
    settings.embedding_dim = dimension
    settings.embedding_version = f"{model}-{dimension}-test"
    settings.embedding_batch_size = batch_size
    return settings


class FakeEmbedding:
    def __init__(self, values: list[float]) -> None:
        self.values = values


class FakeEmbeddingResponse:
    def __init__(self, values: list[float]) -> None:
        self.embeddings = [FakeEmbedding(values)]


class FakeEmbeddingBatchResponse:
    def __init__(self, vectors: list[list[float]]) -> None:
        self.embeddings = [FakeEmbedding(values) for values in vectors]


class FakeModels:
    def __init__(self, responses: list[object] | None = None, errors: list[BaseException] | None = None) -> None:
        self.responses = responses or []
        self.errors = errors or []
        self.calls: list[dict] = []

    def embed_content(self, **kwargs):
        self.calls.append(kwargs)
        if self.errors:
            raise self.errors.pop(0)
        if self.responses:
            return self.responses.pop(0)
        return FakeEmbeddingResponse([0.1, 0.2, 0.3])


class FakeClient:
    def __init__(self, models: FakeModels) -> None:
        self.models = models


class FakeTypes:
    class Part:
        def __init__(self, text: str) -> None:
            self.text = text

        @classmethod
        def from_text(cls, *, text: str):
            return cls(text)

    class Content:
        def __init__(self, *, parts) -> None:
            self.parts = parts

    class EmbedContentConfig:
        def __init__(
            self,
            *,
            task_type: str | None = None,
            output_dimensionality: int | None = None,
        ) -> None:
            self.task_type = task_type
            self.output_dimensionality = output_dimensionality


async def _no_sleep(_: float) -> None:
    return None


@pytest.mark.asyncio
async def test_embed_batches_separate_contents_and_preserves_vector_order() -> None:
    models = FakeModels(
        responses=[
            FakeEmbeddingBatchResponse(
                [
                    [0.1, 0.2, 0.3],
                    [0.4, 0.5, 0.6],
                ]
            ),
            FakeEmbeddingResponse([0.7, 0.8, 0.9]),
        ]
    )
    provider = GeminiEmbeddingProvider(
        settings=_settings(batch_size=2),
        client=FakeClient(models),
        types_module=FakeTypes,
    )

    vectors = await provider.embed(["text one", "text two", "text three"])

    assert vectors == [
        [0.1, 0.2, 0.3],
        [0.4, 0.5, 0.6],
        [0.7, 0.8, 0.9],
    ]
    assert len(models.calls) == 2
    assert [content.parts[0].text for content in models.calls[0]["contents"]] == [
        "text one",
        "text two",
    ]
    assert [content.parts[0].text for content in models.calls[1]["contents"]] == [
        "text three",
    ]
    assert models.calls[0]["config"].output_dimensionality == 3


@pytest.mark.asyncio
async def test_embed_query_uses_same_model_for_query_text() -> None:
    models = FakeModels()
    provider = GeminiEmbeddingProvider(
        settings=_settings(),
        client=FakeClient(models),
        types_module=FakeTypes,
    )

    vector = await provider.embed_query("task: search result | query: câu hỏi")

    assert vector == [0.1, 0.2, 0.3]
    assert models.calls[0]["model"] == "gemini-embedding-2"
    assert models.calls[0]["contents"] == "task: search result | query: câu hỏi"
    assert models.calls[0]["config"].output_dimensionality == 3
    assert models.calls[0]["config"].task_type is None


@pytest.mark.asyncio
async def test_gemini_embedding_001_uses_task_type_config() -> None:
    models = FakeModels()
    provider = GeminiEmbeddingProvider(
        settings=_settings(model="gemini-embedding-001"),
        client=FakeClient(models),
        types_module=FakeTypes,
    )

    await provider.embed(["document text"])
    await provider.embed_query("query text")

    assert models.calls[0]["config"].task_type == "RETRIEVAL_DOCUMENT"
    assert models.calls[1]["config"].task_type == "RETRIEVAL_QUERY"


@pytest.mark.asyncio
async def test_dimension_mismatch_fails_fast() -> None:
    models = FakeModels(responses=[FakeEmbeddingResponse([0.1, 0.2])])
    provider = GeminiEmbeddingProvider(
        settings=_settings(dimension=3),
        client=FakeClient(models),
        types_module=FakeTypes,
    )

    with pytest.raises(GeminiEmbeddingProviderError, match="dimension mismatch"):
        await provider.embed(["bad dimension"])


@pytest.mark.asyncio
async def test_retryable_error_is_retried() -> None:
    models = FakeModels(errors=[RuntimeError("429 RESOURCE_EXHAUSTED")])
    provider = GeminiEmbeddingProvider(
        settings=_settings(),
        client=FakeClient(models),
        max_retries=1,
        retry_base_delay_seconds=0,
        retry_jitter_seconds=0,
        sleep=_no_sleep,
        types_module=FakeTypes,
    )

    vector = await provider.embed(["retry me"])

    assert vector == [[0.1, 0.2, 0.3]]
    assert len(models.calls) == 2


@pytest.mark.asyncio
async def test_non_retryable_error_fails_without_retry() -> None:
    models = FakeModels(errors=[RuntimeError("bad request")])
    provider = GeminiEmbeddingProvider(
        settings=_settings(),
        client=FakeClient(models),
        max_retries=3,
        sleep=_no_sleep,
        types_module=FakeTypes,
    )

    with pytest.raises(GeminiEmbeddingProviderError, match="bad request"):
        await provider.embed(["fail"])

    assert len(models.calls) == 1


@pytest.mark.asyncio
async def test_empty_text_fails_before_provider_call() -> None:
    models = FakeModels()
    provider = GeminiEmbeddingProvider(
        settings=_settings(),
        client=FakeClient(models),
        types_module=FakeTypes,
    )

    with pytest.raises(ValueError, match="empty text"):
        await provider.embed(["  "])

    assert models.calls == []


def test_extract_first_embedding_values_supports_single_embedding_shape() -> None:
    result = SimpleNamespace(embedding=FakeEmbedding([1, 2, 3]))

    assert extract_first_embedding_values(result) == [1.0, 2.0, 3.0]


def test_extract_embedding_values_returns_every_vector() -> None:
    result = FakeEmbeddingBatchResponse([[1, 2, 3], [4, 5, 6]])

    assert extract_embedding_values(result) == [
        [1.0, 2.0, 3.0],
        [4.0, 5.0, 6.0],
    ]


@pytest.mark.asyncio
async def test_batch_vector_count_mismatch_fails_fast() -> None:
    models = FakeModels(responses=[FakeEmbeddingResponse([0.1, 0.2, 0.3])])
    provider = GeminiEmbeddingProvider(
        settings=_settings(batch_size=2),
        client=FakeClient(models),
        types_module=FakeTypes,
    )

    with pytest.raises(GeminiEmbeddingProviderError, match="vector count mismatch"):
        await provider.embed(["first", "second"])
