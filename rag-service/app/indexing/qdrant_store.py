from __future__ import annotations

from typing import Any
from uuid import UUID, uuid5

from app.core.config import Settings, get_settings
from app.core.logger import get_logger
from app.indexing.base import SearchResult, VectorChunk, VectorSearchQuery, VectorStore
from app.indexing.qdrant_filters import build_qdrant_filter


logger = get_logger(__name__)

QDRANT_POINT_NAMESPACE = UUID("8a1bb697-6b0e-4e1d-9cf6-1f8bf2bb0d53")
VECTOR_PAYLOAD_SCHEMA_VERSION = "rag-vector-payload-v1"

# Qdrant payload should stay retrieval/filter/citation focused. PostgreSQL and
# rag-artifacts keep the larger debug/report metadata.
QDRANT_PAYLOAD_ALLOWLIST = {
    "document_id",
    "documentId",
    "external_document_id",
    "source_system",
    "sourceType",
    "source_type",
    "source_id",
    "content_type",
    "book_id",
    "bookId",
    "ebook_id",
    "ebookId",
    "page_number",
    "pageStart",
    "pageEnd",
    "chapter_index",
    "chapter_number",
    "chapter_title",
    "section_path",
    "section_id",
    "chapter_source_page",
    "chunk_index",
    "chunkIndex",
    "chunk_hash",
    "chunker",
    "chunk_size",
    "chunk_overlap",
    "token_count",
    "token_counter",
    "chunking_strategy",
    "chunking_strategy_version",
    "document_profile",
    "document_profile_version",
    "cleaning_version",
    "embedding_provider",
    "embedding_model",
    "embedding_dim",
    "embedding_version",
    "embedding_text_policy",
    "embedding_text_hash",
    "metadata_schema_version",
    "document_version_id",
    "artifact_version_key",
    "source_checksum_sha256",
    "document_version",
    "source_version",
    "artifact_version",
    "active",
    "visibility",
    "tenant_id",
    "workspace_id",
    "allowed_role_keys",
}

QDRANT_FILTER_INDEX_FIELDS = {
    "active": "bool",
    "document_id": "integer",
    "book_id": "integer",
    "ebook_id": "integer",
    "embedding_version": "keyword",
    "sourceType": "keyword",
    "source_type": "keyword",
    "tenant_id": "keyword",
    "workspace_id": "keyword",
}


class QdrantVectorStoreConfigError(RuntimeError):
    """Raised when Qdrant collection config does not match embedding config."""


class QdrantVectorStore(VectorStore):
    def __init__(
        self,
        *,
        settings: Settings | None = None,
        client=None,
        collection_name: str | None = None,
        vector_size: int | None = None,
        distance: str | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.collection_name = collection_name or self.settings.qdrant_collection_name
        self.vector_size = vector_size or self.settings.embedding_dim
        self.distance = _normalize_distance(distance or self.settings.embedding_distance_metric)
        self.client = client or self._build_client()
        self._collection_ready = False

    async def ensure_collection(self) -> None:
        """Create or validate the Qdrant collection used by RAG chunks.

        This protects the common production failure where the collection vector
        dimension or distance metric no longer matches the active embedding
        model. It also creates small payload indexes for fields that retrieval
        will filter on often.
        """

        if self._collection_ready:
            return

        if not await self._collection_exists():
            logger.info(
                "qdrant_collection_create_started",
                collection_name=self.collection_name,
                vector_size=self.vector_size,
                distance=self.distance,
            )
            await self.client.create_collection(
                collection_name=self.collection_name,
                vectors_config=self._build_vector_params(),
            )
            logger.info(
                "qdrant_collection_created",
                collection_name=self.collection_name,
                vector_size=self.vector_size,
                distance=self.distance,
            )
        else:
            collection_info = await self.client.get_collection(self.collection_name)
            actual_size, actual_distance = _extract_vector_config(collection_info)
            if actual_size != self.vector_size:
                raise QdrantVectorStoreConfigError(
                    (
                        f"Qdrant collection '{self.collection_name}' vector size mismatch: "
                        f"expected {self.vector_size}, got {actual_size}."
                    )
                )
            if _normalize_distance(actual_distance) != self.distance:
                raise QdrantVectorStoreConfigError(
                    (
                        f"Qdrant collection '{self.collection_name}' distance mismatch: "
                        f"expected {self.distance}, got {_normalize_distance(actual_distance)}."
                    )
                )

        await self.ensure_payload_indexes()
        self._collection_ready = True
        logger.info(
            "qdrant_collection_ready",
            collection_name=self.collection_name,
            vector_size=self.vector_size,
            distance=self.distance,
        )

    async def ensure_payload_indexes(self) -> None:
        """Create Qdrant payload indexes used by metadata/permission filters."""

        if not hasattr(self.client, "create_payload_index"):
            return

        for field_name, field_schema in self._build_payload_index_schemas().items():
            try:
                await self.client.create_payload_index(
                    collection_name=self.collection_name,
                    field_name=field_name,
                    field_schema=field_schema,
                    wait=True,
                )
                logger.info(
                    "qdrant_payload_index_ready",
                    collection_name=self.collection_name,
                    field_name=field_name,
                    field_schema=str(field_schema),
                )
            except Exception as error:  # noqa: BLE001 - Qdrant versions expose different errors.
                if _looks_like_existing_index_error(error):
                    logger.info(
                        "qdrant_payload_index_already_exists",
                        collection_name=self.collection_name,
                        field_name=field_name,
                    )
                    continue
                raise

    async def upsert(self, chunks: list[VectorChunk]) -> None:
        """Idempotently write embedded chunks into Qdrant.

        `VectorChunk.id` remains the human-readable vector key stored in
        PostgreSQL. Qdrant point IDs are deterministic UUIDs derived from that
        key plus `embedding_version`, because Qdrant point IDs should be stable
        and SDK-compatible.
        """

        if not chunks:
            return

        await self.ensure_collection()
        points = [self._build_point(chunk) for chunk in chunks]
        logger.info(
            "qdrant_upsert_started",
            collection_name=self.collection_name,
            point_count=len(points),
            vector_size=self.vector_size,
            distance=self.distance,
        )
        await self.client.upsert(
            collection_name=self.collection_name,
            points=points,
            wait=True,
        )
        logger.info(
            "qdrant_upsert_completed",
            collection_name=self.collection_name,
            point_count=len(points),
        )

    async def search(self, query: VectorSearchQuery) -> list[SearchResult]:
        """Search Qdrant with mandatory metadata filters already attached.

        The caller must build `VectorSearchQuery` through a retrieval service or
        a trusted scope builder. This method refuses dimension-mismatched query
        vectors and delegates filter conversion to `build_qdrant_filter()`.
        """

        self._validate_query_vector(query.query_vector)
        await self.ensure_collection()
        qdrant_filter = build_qdrant_filter(query.filters)

        logger.info(
            "qdrant_search_started",
            collection_name=self.collection_name,
            top_k=query.top_k,
            score_threshold=query.score_threshold,
            include_vectors=query.include_vectors,
            filter_keys=sorted(query.filters.keys()),
        )
        points = await self._search_points(
            collection_name=self.collection_name,
            query_vector=query.query_vector,
            query_filter=qdrant_filter,
            limit=query.top_k,
            score_threshold=query.score_threshold,
            with_payload=True,
            with_vectors=query.include_vectors,
        )
        results = [_point_to_search_result(point) for point in points]
        logger.info(
            "qdrant_search_completed",
            collection_name=self.collection_name,
            result_count=len(results),
            top_score=results[0].score if results else None,
        )
        return results

    async def delete(self, chunk_ids: list[str]) -> None:
        raise NotImplementedError

    async def _search_points(
        self,
        *,
        collection_name: str,
        query_vector: list[float],
        query_filter,
        limit: int,
        score_threshold: float | None,
        with_payload: bool,
        with_vectors: bool,
    ):
        """Search points across qdrant-client API versions.

        Older qdrant-client versions exposed `AsyncQdrantClient.search()`.
        Newer versions use `query_points()`. Keeping this compatibility shim
        lets the project run with both local/dev and rebuilt Docker images.
        """

        if hasattr(self.client, "search"):
            return await self.client.search(
                collection_name=collection_name,
                query_vector=query_vector,
                query_filter=query_filter,
                limit=limit,
                score_threshold=score_threshold,
                with_payload=with_payload,
                with_vectors=with_vectors,
            )

        if hasattr(self.client, "query_points"):
            response = await self.client.query_points(
                collection_name=collection_name,
                query=query_vector,
                query_filter=query_filter,
                limit=limit,
                score_threshold=score_threshold,
                with_payload=with_payload,
                with_vectors=with_vectors,
            )
            return list(getattr(response, "points", None) or [])

        raise QdrantVectorStoreConfigError(
            "Qdrant client does not expose search() or query_points()."
        )

    def _build_client(self):
        try:
            from qdrant_client import AsyncQdrantClient
        except ImportError as error:  # pragma: no cover - depends on environment install.
            raise QdrantVectorStoreConfigError("qdrant-client is required for QdrantVectorStore.") from error

        return AsyncQdrantClient(
            url=self.settings.qdrant_url,
            api_key=self.settings.qdrant_api_key or None,
        )

    def _build_vector_params(self):
        try:
            from qdrant_client.models import Distance, VectorParams
        except ImportError as error:  # pragma: no cover - depends on environment install.
            raise QdrantVectorStoreConfigError("qdrant-client models are required for QdrantVectorStore.") from error

        return VectorParams(
            size=self.vector_size,
            distance=_to_qdrant_distance(self.distance, Distance),
        )

    def _build_point(self, chunk: VectorChunk):
        self._validate_chunk_vector(chunk)
        try:
            from qdrant_client.models import PointStruct
        except ImportError as error:  # pragma: no cover - depends on environment install.
            raise QdrantVectorStoreConfigError("qdrant-client models are required for QdrantVectorStore.") from error

        vector_key = self._build_vector_key(chunk)
        point_id = deterministic_qdrant_point_id(vector_key)
        return PointStruct(
            id=point_id,
            vector=chunk.vector,
            payload=self._build_payload(chunk, point_id=point_id, vector_key=vector_key),
        )

    def _build_payload(self, chunk: VectorChunk, *, point_id: str, vector_key: str) -> dict[str, Any]:
        metadata = dict(chunk.metadata or {})
        payload: dict[str, Any] = {
            "qdrant_point_id": point_id,
            "qdrant_point_key": vector_key,
            "vector_id": metadata.get("vector_id") or chunk.id,
            "content": chunk.text,
            "active": _bool_or_default(metadata.get("active"), True),
            "metadata_schema_version": metadata.get("metadata_schema_version") or VECTOR_PAYLOAD_SCHEMA_VERSION,
            "embedding_model": metadata.get("embedding_model") or self.settings.embedding_model,
            "embedding_dim": metadata.get("embedding_dim") or self.vector_size,
            "embedding_version": metadata.get("embedding_version") or self.settings.embedding_version,
        }

        for key in QDRANT_PAYLOAD_ALLOWLIST:
            if key in metadata:
                value = _to_qdrant_payload_value(metadata[key])
                if value is not None:
                    payload[key] = value

        if "source_system" not in payload and str(payload.get("sourceType", "")).startswith("LIBRARY"):
            payload["source_system"] = "LIBRARY"

        return payload

    def _build_vector_key(self, chunk: VectorChunk) -> str:
        embedding_version = str((chunk.metadata or {}).get("embedding_version") or self.settings.embedding_version)
        return f"{chunk.id}:{embedding_version}"

    def _validate_chunk_vector(self, chunk: VectorChunk) -> None:
        if len(chunk.vector) != self.vector_size:
            raise QdrantVectorStoreConfigError(
                (
                    f"Vector dimension mismatch for chunk '{chunk.id}': "
                    f"expected {self.vector_size}, got {len(chunk.vector)}."
                )
            )

    def _validate_query_vector(self, query_vector: list[float]) -> None:
        if len(query_vector) != self.vector_size:
            raise QdrantVectorStoreConfigError(
                (
                    "Query vector dimension mismatch: "
                    f"expected {self.vector_size}, got {len(query_vector)}."
                )
            )

    def _build_payload_index_schemas(self) -> dict[str, Any]:
        try:
            from qdrant_client.models import PayloadSchemaType
        except ImportError as error:  # pragma: no cover - depends on environment install.
            raise QdrantVectorStoreConfigError("qdrant-client models are required for QdrantVectorStore.") from error

        mapping = {
            "keyword": getattr(PayloadSchemaType, "KEYWORD", "keyword"),
            "integer": getattr(PayloadSchemaType, "INTEGER", "integer"),
            "bool": getattr(PayloadSchemaType, "BOOL", getattr(PayloadSchemaType, "BOOLEAN", "bool")),
        }
        return {
            field_name: mapping[schema_name]
            for field_name, schema_name in QDRANT_FILTER_INDEX_FIELDS.items()
        }

    async def _collection_exists(self) -> bool:
        if hasattr(self.client, "collection_exists"):
            return bool(await self.client.collection_exists(self.collection_name))

        try:
            await self.client.get_collection(self.collection_name)
            return True
        except Exception:  # noqa: BLE001 - qdrant-client exception types vary by transport/version.
            return False


def _to_qdrant_distance(distance: str, distance_enum):
    normalized = _normalize_distance(distance)
    mapping = {
        "Cosine": getattr(distance_enum, "COSINE"),
        "Dot": getattr(distance_enum, "DOT"),
        "Euclid": getattr(distance_enum, "EUCLID"),
        "Manhattan": getattr(distance_enum, "MANHATTAN", getattr(distance_enum, "EUCLID")),
    }
    return mapping[normalized]


def _normalize_distance(distance) -> str:
    value = getattr(distance, "value", distance)
    value = getattr(value, "name", value)
    normalized = str(value).strip().lower()
    mapping = {
        "cosine": "Cosine",
        "dot": "Dot",
        "euclid": "Euclid",
        "euclidean": "Euclid",
        "manhattan": "Manhattan",
    }
    if normalized not in mapping:
        raise QdrantVectorStoreConfigError(f"Unsupported Qdrant distance metric: {distance}")
    return mapping[normalized]


def _extract_vector_config(collection_info) -> tuple[int, str]:
    vectors_config = _get_nested_attr(collection_info, "config", "params", "vectors")
    if vectors_config is None:
        vectors_config = _get_nested_attr(collection_info, "vectors")

    if isinstance(vectors_config, dict):
        # Named-vector collections store a mapping. The MVP uses a single vector.
        if "size" not in vectors_config and vectors_config:
            vectors_config = next(iter(vectors_config.values()))
        size = _get_value(vectors_config, "size")
        distance = _get_value(vectors_config, "distance")
    else:
        size = _get_value(vectors_config, "size")
        distance = _get_value(vectors_config, "distance")

    if size is None or distance is None:
        raise QdrantVectorStoreConfigError("Could not read Qdrant collection vector config.")

    return int(size), _normalize_distance(distance)


def _get_nested_attr(value, *path):
    current = value
    for key in path:
        current = _get_value(current, key)
        if current is None:
            return None
    return current


def _get_value(value, key: str):
    if value is None:
        return None
    if isinstance(value, dict):
        return value.get(key)
    return getattr(value, key, None)


def deterministic_qdrant_point_id(vector_key: str) -> str:
    """Build a stable Qdrant-compatible UUID point id from a readable key."""

    return str(uuid5(QDRANT_POINT_NAMESPACE, vector_key))


def _to_qdrant_payload_value(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, str):
        normalized = value.strip()
        return normalized or None
    if isinstance(value, bool | int | float):
        return value
    if isinstance(value, (list, tuple, set)):
        normalized_items = [_to_qdrant_payload_value(item) for item in value]
        return [item for item in normalized_items if item is not None]
    return None


def _bool_or_default(value: Any, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "y"}:
            return True
        if normalized in {"false", "0", "no", "n"}:
            return False
    return default


def _looks_like_existing_index_error(error: Exception) -> bool:
    message = str(error).lower()
    return "already exists" in message or "already has" in message


def _point_to_search_result(point) -> SearchResult:
    payload = dict(getattr(point, "payload", None) or {})
    point_id = str(getattr(point, "id", "") or payload.get("qdrant_point_id") or "")
    vector_id = payload.get("vector_id")
    vector_id = str(vector_id) if vector_id is not None else None
    score = float(getattr(point, "score", 0.0))
    text = str(payload.get("content") or "")
    return SearchResult(
        id=point_id,
        vector_id=vector_id,
        score=score,
        text=text,
        metadata=payload,
        retrieval_source="vector",
    )
