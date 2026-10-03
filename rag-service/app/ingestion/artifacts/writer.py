from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
from io import BytesIO
import json
import re
from typing import Any

from llama_index.core import Document as LlamaDocument
from sqlalchemy.ext.asyncio import AsyncSession

from app.documents.models import Document
from app.documents.object_storage import ObjectStorageAdapter, StoredObject, get_object_storage_adapter
from app.documents.repository import (
    create_document_artifact,
    get_document_artifact,
    update_document_artifact,
)
from app.core.logger import get_logger
from app.ingestion.chunkers.base import Chunk
from app.ingestion.chunking import ChunkQualityReport
from app.ingestion.parsers.base import ParsedDocument

ARTIFACT_PARSED_TEXT = "PARSED_TEXT"
ARTIFACT_CLEANED_TEXT = "CLEANED_TEXT"
ARTIFACT_PARSED_PAGES_JSONL = "PARSED_PAGES_JSONL"
ARTIFACT_CLEANED_PAGES_JSONL = "CLEANED_PAGES_JSONL"
ARTIFACT_PROCESSED_DOC_MD = "PROCESSED_DOC_MD"
ARTIFACT_CHUNKS_JSONL = "CHUNKS_JSONL"
ARTIFACT_CHUNK_QUALITY_REPORT = "CHUNK_QUALITY_REPORT"
ARTIFACT_MANIFEST = "INGESTION_MANIFEST"
logger = get_logger(__name__)


@dataclass(frozen=True)
class IngestionArtifactSet:
    """Uploaded artifact summary for one document ingestion run."""

    bucket: str
    prefix: str
    version: str
    artifacts: dict[str, str]
    manifest_object_key: str

    def to_metadata(self) -> dict[str, Any]:
        """Return JSON-friendly metadata to store on document/job rows."""

        return {
            "artifact_bucket": self.bucket,
            "artifact_prefix": self.prefix,
            "artifact_version": self.version,
            "manifest_object_key": self.manifest_object_key,
            "artifacts": dict(self.artifacts),
        }


class IngestionArtifactWriter:
    """Write parser/cleaner/chunker outputs to the durable RAG artifact bucket."""

    def __init__(self, storage: ObjectStorageAdapter | None = None) -> None:
        self.storage = storage or get_object_storage_adapter()

    async def write_ingestion_artifacts(
        self,
        session: AsyncSession,
        *,
        document: Document,
        ingestion_job_id: int | None,
        source_checksum_sha256: str,
        parsed_documents: list[ParsedDocument],
        cleaned_documents: list[LlamaDocument],
        chunks: list[Chunk],
        chunk_quality_report: ChunkQualityReport,
    ) -> IngestionArtifactSet:
        """Upload all MVP ingestion artifacts and register their latest DB pointers."""

        version = _version_from_checksum(source_checksum_sha256)
        prefix = build_ingestion_artifact_prefix(document, version)
        parser_name = _first_metadata_value(parsed_documents, "parser") or _first_metadata_value(
            parsed_documents,
            "source_parser",
        )
        common_metadata = {
            "ingestion_job_id": ingestion_job_id,
            "artifact_version": version,
            "artifact_prefix": prefix,
            "source_checksum_sha256": source_checksum_sha256,
            "parser": parser_name,
        }

        uploaded: dict[str, StoredObject] = {}
        artifact_paths: dict[str, str] = {}
        logger.info(
            "ingestion_artifact_write_started",
            document_id=document.id,
            external_document_id=document.external_document_id,
            ingestion_job_id=ingestion_job_id,
            artifact_prefix=prefix,
            artifact_version=version,
            parsed_page_count=len(parsed_documents),
            cleaned_page_count=len(cleaned_documents),
            chunk_count=len(chunks),
        )

        async def upload_artifact(
            artifact_type: str,
            relative_path: str,
            payload: str,
            content_type: str,
            metadata: dict[str, Any] | None = None,
        ) -> None:
            object_key = f"{prefix}/{relative_path}"
            stored_object = await self._upload_text(object_key, payload, content_type=content_type)
            uploaded[artifact_type] = stored_object
            artifact_paths[artifact_type] = relative_path
            logger.info(
                "ingestion_artifact_uploaded",
                document_id=document.id,
                ingestion_job_id=ingestion_job_id,
                artifact_type=artifact_type,
                bucket=stored_object.bucket,
                object_key=stored_object.object_key,
                size_bytes=stored_object.size_bytes,
                content_type=stored_object.content_type,
            )
            await self._upsert_artifact_record(
                session,
                document_id=document.id,
                artifact_type=artifact_type,
                stored_object=stored_object,
                metadata={
                    **common_metadata,
                    **(metadata or {}),
                    "relative_path": relative_path,
                },
            )

        await upload_artifact(
            ARTIFACT_PARSED_TEXT,
            "parsed_text.txt",
            _pages_to_text(parsed_documents),
            "text/plain; charset=utf-8",
        )
        await upload_artifact(
            ARTIFACT_CLEANED_TEXT,
            "cleaned_text.txt",
            _cleaned_documents_to_text(cleaned_documents),
            "text/plain; charset=utf-8",
        )
        await upload_artifact(
            ARTIFACT_PARSED_PAGES_JSONL,
            "parsed/parsed_pages.jsonl",
            _parsed_documents_to_jsonl(parsed_documents),
            "application/x-ndjson",
        )
        await upload_artifact(
            ARTIFACT_CLEANED_PAGES_JSONL,
            "cleaned/cleaned_pages.jsonl",
            _cleaned_documents_to_jsonl(cleaned_documents),
            "application/x-ndjson",
        )
        await upload_artifact(
            ARTIFACT_PROCESSED_DOC_MD,
            "processed_doc.md",
            _cleaned_documents_to_text(cleaned_documents),
            "text/markdown; charset=utf-8",
        )
        await upload_artifact(
            ARTIFACT_CHUNKS_JSONL,
            "chunks/chunks.jsonl",
            _chunks_to_jsonl(chunks),
            "application/x-ndjson",
            metadata={"chunk_count": len(chunks)},
        )
        await upload_artifact(
            ARTIFACT_CHUNK_QUALITY_REPORT,
            "reports/chunk_quality_report.json",
            _json_dumps(chunk_quality_report.to_metadata()),
            "application/json",
            metadata={"chunk_quality_status": chunk_quality_report.status},
        )

        manifest_relative_path = "manifest.json"
        manifest = _build_manifest(
            document=document,
            ingestion_job_id=ingestion_job_id,
            version=version,
            prefix=prefix,
            source_checksum_sha256=source_checksum_sha256,
            parsed_documents=parsed_documents,
            cleaned_documents=cleaned_documents,
            chunks=chunks,
            chunk_quality_report=chunk_quality_report,
            artifacts={**artifact_paths, ARTIFACT_MANIFEST: manifest_relative_path},
        )
        await upload_artifact(
            ARTIFACT_MANIFEST,
            manifest_relative_path,
            _json_dumps(manifest),
            "application/json",
        )

        manifest_object = uploaded[ARTIFACT_MANIFEST]
        logger.info(
            "ingestion_artifact_write_completed",
            document_id=document.id,
            ingestion_job_id=ingestion_job_id,
            artifact_bucket=manifest_object.bucket,
            artifact_prefix=prefix,
            manifest_object_key=manifest_object.object_key,
            artifact_count=len(uploaded),
        )
        return IngestionArtifactSet(
            bucket=manifest_object.bucket,
            prefix=prefix,
            version=version,
            artifacts=artifact_paths,
            manifest_object_key=manifest_object.object_key,
        )

    async def _upload_text(self, object_key: str, payload: str, *, content_type: str) -> StoredObject:
        data = payload.encode("utf-8")
        digest = hashlib.sha256(data).hexdigest()
        return await self.storage.upload_fileobj(
            BytesIO(data),
            object_key=object_key,
            content_type=content_type,
            size_bytes=len(data),
            sha256=digest,
        )

    async def _upsert_artifact_record(
        self,
        session: AsyncSession,
        *,
        document_id: int,
        artifact_type: str,
        stored_object: StoredObject,
        metadata: dict[str, Any],
    ) -> None:
        existing = await get_document_artifact(session, document_id, artifact_type)
        if existing is None:
            await create_document_artifact(
                session,
                document_id=document_id,
                artifact_type=artifact_type,
                bucket=stored_object.bucket,
                object_key=stored_object.object_key,
                content_type=stored_object.content_type,
                size_bytes=stored_object.size_bytes,
                checksum_sha256=stored_object.sha256,
                metadata=metadata,
            )
            return

        await update_document_artifact(
            session,
            existing,
            bucket=stored_object.bucket,
            object_key=stored_object.object_key,
            content_type=stored_object.content_type,
            size_bytes=stored_object.size_bytes,
            checksum_sha256=stored_object.sha256,
            metadata=metadata,
        )


def build_ingestion_artifact_prefix(document: Document, version: str) -> str:
    """Build the durable prefix under rag-artifacts for one document version."""

    document_key = _safe_key_part(getattr(document, "external_document_id", "") or f"doc_{document.id}")
    return f"documents/{document_key}/versions/{version}"


def _version_from_checksum(checksum_sha256: str) -> str:
    checksum = (checksum_sha256 or "").strip().lower()
    if len(checksum) >= 8:
        return f"v{checksum[:8]}"
    return "vunknown"


def _safe_key_part(value: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    return sanitized.strip("._-") or "unknown"


def _pages_to_text(parsed_documents: list[ParsedDocument]) -> str:
    pages = []
    for index, parsed_document in enumerate(parsed_documents, start=1):
        page_number = _resolve_page_number(parsed_document.metadata, fallback=index)
        pages.append(f"<!-- page: {page_number} -->\n{parsed_document.text or ''}".strip())
    return "\n\n".join(pages).strip() + "\n"


def _cleaned_documents_to_text(cleaned_documents: list[LlamaDocument]) -> str:
    pages = []
    for index, document in enumerate(cleaned_documents, start=1):
        page_number = _resolve_page_number(document.metadata, fallback=index)
        pages.append(f"<!-- page: {page_number} -->\n{document.text or ''}".strip())
    return "\n\n".join(pages).strip() + "\n"


def _parsed_documents_to_jsonl(parsed_documents: list[ParsedDocument]) -> str:
    rows = []
    for index, parsed_document in enumerate(parsed_documents, start=1):
        rows.append(
            _json_dumps(
                {
                    "page_number": _resolve_page_number(parsed_document.metadata, fallback=index),
                    "text": parsed_document.text or "",
                    "metadata": dict(parsed_document.metadata or {}),
                }
            )
        )
    return "\n".join(rows) + ("\n" if rows else "")


def _cleaned_documents_to_jsonl(cleaned_documents: list[LlamaDocument]) -> str:
    rows = []
    for index, document in enumerate(cleaned_documents, start=1):
        rows.append(
            _json_dumps(
                {
                    "page_number": _resolve_page_number(document.metadata, fallback=index),
                    "text": document.text or "",
                    "metadata": dict(document.metadata or {}),
                }
            )
        )
    return "\n".join(rows) + ("\n" if rows else "")


def _chunks_to_jsonl(chunks: list[Chunk]) -> str:
    rows = []
    for index, chunk in enumerate(chunks):
        rows.append(
            _json_dumps(
                {
                    "chunk_index": index,
                    "text": chunk.text,
                    "metadata": dict(chunk.metadata or {}),
                    "vector_id": chunk.metadata.get("vector_id"),
                }
            )
        )
    return "\n".join(rows) + ("\n" if rows else "")


def _build_manifest(
    *,
    document: Document,
    ingestion_job_id: int | None,
    version: str,
    prefix: str,
    source_checksum_sha256: str,
    parsed_documents: list[ParsedDocument],
    cleaned_documents: list[LlamaDocument],
    chunks: list[Chunk],
    chunk_quality_report: ChunkQualityReport,
    artifacts: dict[str, str],
) -> dict[str, Any]:
    first_chunk_metadata = dict(chunks[0].metadata or {}) if chunks else {}
    return {
        "schema": "rag_ingestion_artifacts_manifest_v1",
        "created_at": datetime.now(UTC).isoformat(),
        "document_id": document.id,
        "documentId": document.external_document_id,
        "book_id": document.book_id,
        "ebook_id": document.ebook_id,
        "filename": document.filename,
        "ingestion_job_id": ingestion_job_id,
        "version": version,
        "prefix": prefix,
        "source": {
            "storage_path": document.storage_path,
            "checksum_sha256": source_checksum_sha256,
        },
        "parser": {
            "name": _first_metadata_value(parsed_documents, "parser"),
            "page_count": len(parsed_documents),
        },
        "cleaning": {
            "cleaned_page_count": len(cleaned_documents),
            "cleaning_version": first_chunk_metadata.get("cleaning_version"),
            "cleaning_quality_status": first_chunk_metadata.get("cleaning_quality_status"),
        },
        "chunking": {
            "chunk_count": len(chunks),
            "chunking_strategy": first_chunk_metadata.get("chunking_strategy"),
            "chunking_strategy_version": first_chunk_metadata.get("chunking_strategy_version"),
            "chunker": first_chunk_metadata.get("chunker"),
            "chunk_quality_status": chunk_quality_report.status,
            "chunk_quality_report": chunk_quality_report.to_metadata(),
        },
        "artifacts": dict(artifacts),
    }


def _resolve_page_number(metadata: dict | None, *, fallback: int) -> int:
    metadata = metadata or {}
    page_number = metadata.get("page_number")
    if isinstance(page_number, int):
        return page_number
    page_start = metadata.get("pageStart")
    if isinstance(page_start, int):
        return page_start
    return fallback


def _first_metadata_value(parsed_documents: list[ParsedDocument], key: str) -> Any:
    for parsed_document in parsed_documents:
        value = (parsed_document.metadata or {}).get(key)
        if value not in (None, ""):
            return value
    return None


def _json_dumps(payload: Any) -> str:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
