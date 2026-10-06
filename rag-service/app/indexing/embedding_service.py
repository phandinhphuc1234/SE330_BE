from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from app.core.config import Settings, get_settings
from app.core.logger import get_logger
from app.indexing.embedding_provider import EmbeddingProvider
from app.indexing.embedding_text_builder import BuiltEmbeddingText, EmbeddingTextBuilder


logger = get_logger(__name__)


class MutableChunkLike(Protocol):
    """Minimal chunk shape used by the embedding stage.

    The ingestion pipeline currently has an internal `Chunk` dataclass while the
    database has `DocumentChunk` rows. This protocol keeps the service focused
    on the fields both concepts share: clean text and metadata.
    """

    text: str
    metadata: dict[str, Any]


@dataclass(frozen=True)
class EmbeddedChunk:
    """A chunk plus its freshly created vector.

    The vector is intentionally returned in memory instead of being stored in
    PostgreSQL metadata. A3 will send this vector to Qdrant.
    """

    chunk: MutableChunkLike
    vector: list[float]
    embedding_text: BuiltEmbeddingText
    vector_id: str | None


class ChunkEmbeddingService:
    """Build embedding text, call the provider, and attach embedding metadata."""

    def __init__(
        self,
        *,
        provider: EmbeddingProvider,
        text_builder: EmbeddingTextBuilder | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.provider = provider
        self.text_builder = text_builder or EmbeddingTextBuilder(settings=self.settings)

    async def embed_chunks(self, chunks: list[MutableChunkLike]) -> list[EmbeddedChunk]:
        """Embed chunks and update their metadata in-place.

        This stage does not upsert to Qdrant. It marks vectors as ready for the
        next stage via `vector_indexing_status=pending_qdrant_upsert`.
        """

        if not chunks:
            logger.info("chunk_embedding_skipped_empty_input")
            return []

        logger.info(
            "chunk_embedding_started",
            chunk_count=len(chunks),
            provider=_provider_attr(self.provider, "provider_name", self.settings.embedding_provider),
            model=_provider_attr(self.provider, "model_name", self.settings.embedding_model),
            dimension=_provider_attr(self.provider, "dimension", self.settings.embedding_dim),
            version=_provider_attr(self.provider, "version", self.settings.embedding_version),
            batch_size=self.settings.embedding_batch_size,
        )
        built_texts = [
            self.text_builder.build_document_text(chunk.text, chunk.metadata or {})
            for chunk in chunks
        ]
        try:
            vectors = await self.provider.embed([built.text for built in built_texts])
        except Exception as error:
            _mark_embedding_failed(chunks, error)
            logger.error(
                "chunk_embedding_failed",
                chunk_count=len(chunks),
                error=str(error),
                exc_info=True,
            )
            raise

        if len(vectors) != len(chunks):
            error = ValueError(f"Embedding provider returned {len(vectors)} vectors for {len(chunks)} chunks.")
            _mark_embedding_failed(chunks, error)
            logger.error(
                "chunk_embedding_vector_count_mismatch",
                chunk_count=len(chunks),
                vector_count=len(vectors),
                error=str(error),
            )
            raise error

        try:
            embedded_chunks: list[EmbeddedChunk] = []
            for chunk, built_text, vector in zip(chunks, built_texts, vectors, strict=True):
                self._validate_vector_dimension(vector)
                self._attach_success_metadata(chunk, built_text, vector)
                embedded_chunks.append(
                    EmbeddedChunk(
                        chunk=chunk,
                        vector=vector,
                        embedding_text=built_text,
                        vector_id=_string_or_none((chunk.metadata or {}).get("vector_id")),
                    )
                )
        except Exception as error:
            _mark_embedding_failed(chunks, error)
            logger.error(
                "chunk_embedding_metadata_attach_failed",
                chunk_count=len(chunks),
                error=str(error),
                exc_info=True,
            )
            raise

        logger.info(
            "chunk_embedding_completed",
            chunk_count=len(embedded_chunks),
            vector_dimension=len(embedded_chunks[0].vector) if embedded_chunks else None,
            embedding_text_policy=embedded_chunks[0].embedding_text.policy if embedded_chunks else None,
        )
        return embedded_chunks

    def _attach_success_metadata(
        self,
        chunk: MutableChunkLike,
        built_text: BuiltEmbeddingText,
        vector: list[float],
    ) -> None:
        metadata = chunk.metadata or {}
        metadata.update(
            {
                "embedding_status": "embedded",
                "embedding_provider": _provider_attr(self.provider, "provider_name", self.settings.embedding_provider),
                "embedding_model": _provider_attr(self.provider, "model_name", self.settings.embedding_model),
                "embedding_dim": _provider_attr(self.provider, "dimension", self.settings.embedding_dim),
                "embedding_version": _provider_attr(self.provider, "version", self.settings.embedding_version),
                "embedding_text_policy": built_text.policy,
                "embedding_text_hash": built_text.text_hash,
                "embedding_vector_dimension": len(vector),
                # Qdrant is deliberately a separate A3 stage.
                "vector_indexing_status": "pending_qdrant_upsert",
            }
        )
        chunk.metadata = metadata

    def _validate_vector_dimension(self, vector: list[float]) -> None:
        expected_dimension = int(_provider_attr(self.provider, "dimension", self.settings.embedding_dim))
        if len(vector) != expected_dimension:
            raise ValueError(
                f"Embedding vector dimension mismatch: expected {expected_dimension}, got {len(vector)}."
            )


def _mark_embedding_failed(chunks: list[MutableChunkLike], error: BaseException) -> None:
    for chunk in chunks:
        metadata = chunk.metadata or {}
        metadata.update(
            {
                "embedding_status": "failed",
                "embedding_error": str(error),
                "vector_indexing_status": "embedding_failed",
            }
        )
        chunk.metadata = metadata


def _provider_attr(provider: EmbeddingProvider, name: str, fallback: Any) -> Any:
    return getattr(provider, name, fallback)


def _string_or_none(value: Any) -> str | None:
    if value is None:
        return None
    value = str(value).strip()
    return value or None
