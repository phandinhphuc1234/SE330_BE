from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Sequence
import random
from typing import Any

from app.core.config import Settings, get_settings
from app.core.logger import get_logger
from app.indexing.embedding_provider import EmbeddingProvider


logger = get_logger(__name__)


class GeminiEmbeddingProviderError(RuntimeError):
    """Raised when Gemini embedding fails or returns an invalid response."""


class GeminiEmbeddingProvider(EmbeddingProvider):
    """Embedding provider backed by Google Gemini Embedding API.

    Document texts are sent in bounded batches. Every text is wrapped in a
    separate Gemini ``Content`` object, which is important for
    ``gemini-embedding-2``: separate Content objects produce separate vectors,
    while multiple parts inside one Content would be aggregated.
    """

    provider_name = "gemini"

    def __init__(
        self,
        *,
        settings: Settings | None = None,
        api_key: str | None = None,
        model: str | None = None,
        dimension: int | None = None,
        version: str | None = None,
        client: Any | None = None,
        types_module: Any | None = None,
        batch_size: int | None = None,
        max_retries: int | None = None,
        retry_base_delay_seconds: float | None = None,
        retry_max_delay_seconds: float | None = None,
        retry_jitter_seconds: float | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        random_uniform: Callable[[float, float], float] = random.uniform,
    ) -> None:
        self.settings = settings or get_settings()
        self.model_name = model or self.settings.embedding_model
        self.dimension = dimension or self.settings.embedding_dim
        self.version = version or self.settings.embedding_version
        self.batch_size = max(1, batch_size or self.settings.embedding_batch_size)
        effective_max_retries = (
            self.settings.embedding_max_retries if max_retries is None else max_retries
        )
        effective_base_delay = (
            self.settings.embedding_retry_base_delay_seconds
            if retry_base_delay_seconds is None
            else retry_base_delay_seconds
        )
        effective_max_delay = (
            self.settings.embedding_retry_max_delay_seconds
            if retry_max_delay_seconds is None
            else retry_max_delay_seconds
        )
        effective_jitter = (
            self.settings.embedding_retry_jitter_seconds
            if retry_jitter_seconds is None
            else retry_jitter_seconds
        )
        self.max_retries = max(0, effective_max_retries)
        self.retry_base_delay_seconds = max(0.0, effective_base_delay)
        self.retry_max_delay_seconds = max(1.0, effective_max_delay)
        self.retry_jitter_seconds = max(0.0, effective_jitter)
        self._sleep = sleep
        self._random_uniform = random_uniform
        self._types_module = types_module

        if client is not None:
            self._client = client
            return

        effective_api_key = api_key or self.settings.gemini_api_key
        if not effective_api_key:
            raise ValueError("GEMINI_API_KEY is required for GeminiEmbeddingProvider.")

        genai_module, types = _load_google_genai()
        self._types_module = self._types_module or types
        self._client = genai_module.Client(api_key=effective_api_key)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """Embed document/chunk texts in bounded, independently-vectorized batches.

        Empty input returns an empty list. Empty individual text is rejected
        because providers either fail or return low-quality vectors for it.
        """

        normalized_texts = [_normalize_text(text) for text in texts]
        if any(not text for text in normalized_texts):
            raise ValueError("Cannot embed an empty text.")

        vectors: list[list[float]] = []
        total_batches = (len(normalized_texts) + self.batch_size - 1) // self.batch_size
        for batch_index, start in enumerate(range(0, len(normalized_texts), self.batch_size), start=1):
            batch = normalized_texts[start : start + self.batch_size]
            logger.info(
                "gemini_embedding_batch_started",
                batch_index=batch_index,
                total_batches=total_batches,
                batch_size=len(batch),
                model=self.model_name,
            )
            batch_vectors = await self._embed_batch(
                batch,
                task_type="RETRIEVAL_DOCUMENT",
                batch_index=batch_index,
                total_batches=total_batches,
            )
            vectors.extend(batch_vectors)
            logger.info(
                "gemini_embedding_batch_completed",
                batch_index=batch_index,
                total_batches=total_batches,
                batch_size=len(batch),
                vector_count=len(batch_vectors),
                model=self.model_name,
            )
        return vectors

    async def embed_query(self, query_text: str) -> list[float]:
        """Embed a query text using the same model/vector space."""

        normalized_text = _normalize_text(query_text)
        if not normalized_text:
            raise ValueError("Cannot embed an empty query.")
        return await self._embed_one(normalized_text, task_type="RETRIEVAL_QUERY")

    async def _embed_batch(
        self,
        texts: list[str],
        *,
        task_type: str,
        batch_index: int,
        total_batches: int,
    ) -> list[list[float]]:
        """Call Gemini once for a batch and preserve one vector per input text."""

        config = self._build_config(task_type)
        contents = self._build_separate_contents(texts)
        last_error: BaseException | None = None

        for attempt in range(self.max_retries + 1):
            try:
                result = await asyncio.to_thread(self._call_embed_content, contents, config)
                vectors = extract_embedding_values(result)
                if len(vectors) != len(texts):
                    raise GeminiEmbeddingProviderError(
                        "Gemini embedding vector count mismatch: "
                        f"expected {len(texts)}, got {len(vectors)}."
                    )
                for vector in vectors:
                    self._validate_dimension(vector)
                return vectors
            except Exception as error:  # noqa: BLE001 - provider SDK exceptions vary.
                last_error = error
                if attempt >= self.max_retries or not _is_retryable_error(error):
                    break
                delay = self._retry_delay(attempt, error)
                logger.warning(
                    "gemini_embedding_batch_retrying",
                    batch_index=batch_index,
                    total_batches=total_batches,
                    attempt=attempt + 1,
                    max_retries=self.max_retries,
                    retry_delay_seconds=round(delay, 3),
                    error=str(error),
                )
                await self._sleep(delay)

        raise GeminiEmbeddingProviderError(
            f"Gemini embedding batch failed for model {self.model_name}: {last_error}"
        ) from last_error

    async def _embed_one(self, text: str, *, task_type: str) -> list[float]:
        config = self._build_config(task_type)
        last_error: BaseException | None = None

        for attempt in range(self.max_retries + 1):
            try:
                result = await asyncio.to_thread(self._call_embed_content, text, config)
                vector = extract_first_embedding_values(result)
                self._validate_dimension(vector)
                return vector
            except Exception as error:  # noqa: BLE001 - provider SDK exceptions vary.
                last_error = error
                if attempt >= self.max_retries or not _is_retryable_error(error):
                    break
                await self._sleep(self._retry_delay(attempt, error))

        raise GeminiEmbeddingProviderError(
            f"Gemini embedding failed for model {self.model_name}: {last_error}"
        ) from last_error

    def _call_embed_content(self, contents: Any, config: Any | None) -> Any:
        kwargs = {
            "model": self.model_name,
            "contents": contents,
        }
        if config is not None:
            kwargs["config"] = config
        return self._client.models.embed_content(**kwargs)

    def _build_separate_contents(self, texts: list[str]) -> list[Any]:
        """Wrap each chunk as a separate Gemini Content to avoid aggregation."""

        types = self._types_module
        if types is None:
            _, types = _load_google_genai()
            self._types_module = types
        return [
            types.Content(parts=[types.Part.from_text(text=text)])
            for text in texts
        ]

    def _build_config(self, task_type: str) -> Any | None:
        """Build model-compatible task and output-dimension configuration."""

        types = self._types_module
        if types is None:
            _, types = _load_google_genai()
            self._types_module = types
        kwargs: dict[str, Any] = {"output_dimensionality": self.dimension}
        if self.model_name == "gemini-embedding-001":
            kwargs["task_type"] = task_type
        return types.EmbedContentConfig(**kwargs)

    def _validate_dimension(self, vector: Sequence[float]) -> None:
        if len(vector) != self.dimension:
            raise GeminiEmbeddingProviderError(
                f"Gemini embedding dimension mismatch: expected {self.dimension}, got {len(vector)}."
            )

    def _retry_delay(self, attempt: int, error: BaseException) -> float:
        retry_after = _extract_retry_after_seconds(error)
        if retry_after is not None:
            return min(self.retry_max_delay_seconds, max(0.0, retry_after))
        exponential = min(
            self.retry_max_delay_seconds,
            self.retry_base_delay_seconds * (2**attempt),
        )
        jitter = self._random_uniform(0.0, self.retry_jitter_seconds)
        return min(self.retry_max_delay_seconds, exponential + jitter)


def extract_embedding_values(result: Any) -> list[list[float]]:
    """Extract all independent vectors returned by Gemini."""

    embeddings = getattr(result, "embeddings", None)
    if embeddings:
        vectors: list[list[float]] = []
        for embedding in embeddings:
            values = getattr(embedding, "values", None)
            if values is None:
                raise GeminiEmbeddingProviderError(
                    "Gemini returned an embedding without vector values."
                )
            vectors.append([float(value) for value in values])
        return vectors

    embedding = getattr(result, "embedding", None)
    if embedding is not None:
        values = getattr(embedding, "values", None)
        if values is not None:
            return [[float(value) for value in values]]

    raise GeminiEmbeddingProviderError(
        f"Could not extract embedding values from response type {type(result).__name__}."
    )


def extract_first_embedding_values(result: Any) -> list[float]:
    """Extract vector values from google-genai embedding response variants."""

    return extract_embedding_values(result)[0]


def _load_google_genai() -> tuple[Any, Any]:
    try:
        from google import genai
        from google.genai import types
    except ImportError as error:  # pragma: no cover - depends on environment install.
        raise GeminiEmbeddingProviderError(
            "google-genai is required for GeminiEmbeddingProvider. "
            "Install it or keep llama-index-llms-google-genai installed."
        ) from error
    return genai, types


def _normalize_text(text: str) -> str:
    return text.strip()


def _is_retryable_error(error: BaseException) -> bool:
    message = str(error).lower()
    retryable_markers = (
        "429",
        "resource_exhausted",
        "rate limit",
        "quota",
        "timeout",
        "temporarily unavailable",
        "503",
        "500",
    )
    return any(marker in message for marker in retryable_markers)


def _extract_retry_after_seconds(error: BaseException) -> float | None:
    """Read Retry-After from provider HTTP errors when the SDK exposes it."""

    response = getattr(error, "response", None)
    headers = getattr(response, "headers", None)
    if headers is None:
        return None
    value = headers.get("retry-after") or headers.get("Retry-After")
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
