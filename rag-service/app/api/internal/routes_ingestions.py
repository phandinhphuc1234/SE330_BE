"""Internal ingestion API used by the Library service.

These routes are not browser/public upload endpoints. Spring Boot uploads the
PDF to SeaweedFS first, then calls this API with bucket/object-key/checksum
metadata so RAG can create an async ingestion job.
"""

from pathlib import PurePosixPath
from typing import Literal

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.dependencies import get_db
from app.core.exceptions import IngestionError, NotFoundError, ValidationError
from app.core.logger import get_logger
from app.documents.repository import (
    create_document,
    create_document_artifact,
    get_document,
    get_document_artifact,
    get_document_by_source,
    update_document_artifact,
)
from app.ingestion.repository import (
    attach_task_id,
    create_ingestion_job,
    get_ingestion_job,
    get_latest_ingestion_job_for_document,
    mark_job_status,
)
from app.jobs.ingestion_jobs import process_document


router = APIRouter()
logger = get_logger(__name__)


class LibraryEbookIngestionRequest(BaseModel):
    """Payload Spring Boot sends after an ebook PDF is already stored in S3."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    source_type: Literal["LIBRARY_EBOOK"] = Field(alias="sourceType")
    book_id: int = Field(alias="bookId", gt=0)
    ebook_id: int = Field(alias="ebookId", gt=0)
    bucket: str = Field(min_length=3, max_length=63)
    object_key: str = Field(alias="objectKey", min_length=1, max_length=1024)
    checksum_sha256: str = Field(
        alias="checksumSha256",
        pattern=r"^[0-9a-fA-F]{64}$",
    )
    original_filename: str | None = Field(default=None, alias="originalFilename", max_length=512)
    content_type: str | None = Field(default="application/pdf", alias="contentType", max_length=255)
    file_size_bytes: int | None = Field(default=None, alias="fileSizeBytes", gt=0)

    @field_validator("checksum_sha256")
    @classmethod
    def normalize_checksum(cls, value: str) -> str:
        """Store checksums in lowercase so idempotency comparisons are stable."""

        return value.lower()


class IngestionAcceptedResponse(BaseModel):
    """Response returned immediately after RAG accepts or reuses a job."""

    model_config = ConfigDict(populate_by_name=True)

    document_id: str = Field(alias="documentId")
    ingestion_job_id: int = Field(alias="ingestionJobId")
    status: str


class InternalIngestionStatusResponse(IngestionAcceptedResponse):
    """Polling response used by Library to track async ingestion progress."""

    stage: str | None = None
    error_code: str | None = Field(default=None, alias="errorCode")
    error_message: str | None = Field(default=None, alias="errorMessage")


@router.post(
    "",
    response_model=IngestionAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def create_library_ebook_ingestion(
    payload: LibraryEbookIngestionRequest,
    session: AsyncSession = Depends(get_db),
) -> IngestionAcceptedResponse:
    """Register a Library ebook source and enqueue background processing.

    This endpoint only accepts metadata. The PDF bytes stay in SeaweedFS. RAG
    validates ownership conventions, records the source object as a
    ``RAW_ORIGINAL`` artifact, creates an ingestion job, and pushes a Celery
    task for the worker.
    """

    settings = get_settings()
    expected_key = f"ebooks/{payload.book_id}/{payload.ebook_id}/original.pdf"
    logger.info(
        "internal_ingestion_request_received",
        source_type=payload.source_type,
        book_id=payload.book_id,
        ebook_id=payload.ebook_id,
        bucket=payload.bucket,
        object_key=payload.object_key,
        file_size_bytes=payload.file_size_bytes,
        content_type=payload.content_type,
        checksum_sha256_prefix=payload.checksum_sha256[:12],
    )
    # Keep the bucket/key contract strict so RAG never indexes objects outside
    # the Library-owned private ebook namespace by accident.
    if payload.bucket != settings.library_ebook_bucket:
        logger.warning(
            "internal_ingestion_rejected_invalid_bucket",
            expected_bucket=settings.library_ebook_bucket,
            actual_bucket=payload.bucket,
            book_id=payload.book_id,
            ebook_id=payload.ebook_id,
        )
        raise ValidationError(
            "Library ebook ingestion must use the configured private bucket",
            error_code="INVALID_SOURCE_BUCKET",
        )
    if payload.object_key != expected_key:
        logger.warning(
            "internal_ingestion_rejected_invalid_object_key",
            expected_object_key=expected_key,
            actual_object_key=payload.object_key,
            book_id=payload.book_id,
            ebook_id=payload.ebook_id,
        )
        raise ValidationError(
            f"Object key must match {expected_key}",
            error_code="INVALID_LIBRARY_EBOOK_OBJECT_KEY",
        )

    source_id = f"ebook:{payload.ebook_id}"
    external_document_id = f"doc_ebook_{payload.ebook_id}"
    document = await get_document_by_source(session, payload.source_type, source_id)
    artifact = None
    if document is not None:
        # Idempotency: if Library retries the same ebook/checksum while a job is
        # still active or already succeeded, return the existing job instead of
        # creating duplicate chunks/vectors.
        artifact = await get_document_artifact(session, document.id, "RAW_ORIGINAL")
        latest_job = await get_latest_ingestion_job_for_document(session, document.id)
        if (
            artifact is not None
            and artifact.checksum_sha256 == payload.checksum_sha256
            and latest_job is not None
            and latest_job.status != "FAILED"
        ):
            logger.info(
                "internal_ingestion_reused_existing_job",
                document_id=document.id,
                external_document_id=document.external_document_id,
                ingestion_job_id=latest_job.id,
                status=latest_job.status,
                book_id=payload.book_id,
                ebook_id=payload.ebook_id,
            )
            return _accepted_response(document.external_document_id, latest_job.id, latest_job.status)
    else:
        document = await create_document(
            session,
            external_document_id=external_document_id,
            source_type=payload.source_type,
            source_id=source_id,
            book_id=payload.book_id,
            ebook_id=payload.ebook_id,
            filename=payload.original_filename or PurePosixPath(payload.object_key).name,
            storage_path=payload.object_key,
            metadata={
                "storage_backend": "s3",
                "ingestion_status": "QUEUED",
            },
        )
        logger.info(
            "internal_ingestion_document_registered",
            document_id=document.id,
            external_document_id=document.external_document_id,
            source_id=source_id,
            book_id=payload.book_id,
            ebook_id=payload.ebook_id,
        )

    document.book_id = payload.book_id
    document.ebook_id = payload.ebook_id
    document.filename = payload.original_filename or PurePosixPath(payload.object_key).name
    document.storage_path = payload.object_key
    document.metadata_ = {
        **(document.metadata_ or {}),
        "storage_backend": "s3",
        "ingestion_status": "QUEUED",
        "source_type": payload.source_type,
    }

    artifact_metadata = {"original_filename": document.filename}
    if artifact is None:
        # RAW_ORIGINAL is the durable pointer to the source PDF. Worker later
        # uses this bucket/key/checksum to validate and download the file.
        await create_document_artifact(
            session,
            document_id=document.id,
            artifact_type="RAW_ORIGINAL",
            bucket=payload.bucket,
            object_key=payload.object_key,
            content_type=payload.content_type,
            size_bytes=payload.file_size_bytes,
            checksum_sha256=payload.checksum_sha256,
            metadata=artifact_metadata,
        )
        logger.info(
            "internal_ingestion_raw_artifact_registered",
            document_id=document.id,
            bucket=payload.bucket,
            object_key=payload.object_key,
            file_size_bytes=payload.file_size_bytes,
            checksum_sha256_prefix=payload.checksum_sha256[:12],
        )
    else:
        await update_document_artifact(
            session,
            artifact,
            bucket=payload.bucket,
            object_key=payload.object_key,
            content_type=payload.content_type,
            size_bytes=payload.file_size_bytes,
            checksum_sha256=payload.checksum_sha256,
            metadata=artifact_metadata,
        )
        logger.info(
            "internal_ingestion_raw_artifact_updated",
            document_id=document.id,
            bucket=payload.bucket,
            object_key=payload.object_key,
            file_size_bytes=payload.file_size_bytes,
            checksum_sha256_prefix=payload.checksum_sha256[:12],
        )

    job = await create_ingestion_job(
        session,
        document_id=document.id,
        metadata={
            "source_type": payload.source_type,
            "source_id": source_id,
            "book_id": payload.book_id,
            "ebook_id": payload.ebook_id,
            "bucket": payload.bucket,
            "object_key": payload.object_key,
            "checksum_sha256": payload.checksum_sha256,
            "file_size_bytes": payload.file_size_bytes,
            "content_type": payload.content_type,
        },
    )
    await session.commit()
    logger.info(
        "internal_ingestion_job_created",
        document_id=document.id,
        external_document_id=document.external_document_id,
        ingestion_job_id=job.id,
        status=job.status,
        book_id=payload.book_id,
        ebook_id=payload.ebook_id,
    )

    try:
        # The API request should stay fast. Heavy PDF work happens in Celery.
        task = process_document.delay(document.id, job.id)
    except Exception as exc:
        logger.error(
            "internal_ingestion_enqueue_failed",
            document_id=document.id,
            ingestion_job_id=job.id,
            error=str(exc),
            exc_info=True,
        )
        await mark_job_status(
            session,
            job,
            "FAILED",
            stage="enqueue_failed",
            error_message=str(exc),
            completed=True,
        )
        await session.commit()
        raise IngestionError(
            "Failed to enqueue ingestion job",
            error_code="INGESTION_ENQUEUE_FAILED",
        ) from exc

    await attach_task_id(session, job, task.id)
    await session.commit()
    logger.info(
        "internal_ingestion_job_enqueued",
        document_id=document.id,
        external_document_id=document.external_document_id,
        ingestion_job_id=job.id,
        celery_task_id=task.id,
        status=job.status,
    )
    return _accepted_response(document.external_document_id, job.id, job.status)


@router.get("/{job_id}", response_model=InternalIngestionStatusResponse)
async def get_internal_ingestion_status(
    job_id: int,
    session: AsyncSession = Depends(get_db),
) -> InternalIngestionStatusResponse:
    """Return the persisted ingestion job state for Library polling."""

    job = await get_ingestion_job(session, job_id)
    if job is None:
        logger.warning("internal_ingestion_status_not_found", ingestion_job_id=job_id)
        raise NotFoundError(
            f"Ingestion job {job_id} was not found",
            error_code="INGESTION_JOB_NOT_FOUND",
        )
    document = await get_document(session, job.document_id)
    if document is None:
        logger.warning(
            "internal_ingestion_status_document_not_found",
            ingestion_job_id=job_id,
            document_id=job.document_id,
        )
        raise NotFoundError(
            f"Document {job.document_id} was not found",
            error_code="DOCUMENT_NOT_FOUND",
        )
    logger.info(
        "internal_ingestion_status_returned",
        document_id=document.id,
        external_document_id=document.external_document_id,
        ingestion_job_id=job.id,
        status=job.status,
        stage=job.stage,
    )
    return InternalIngestionStatusResponse(
        documentId=document.external_document_id,
        ingestionJobId=job.id,
        status=job.status,
        stage=job.stage,
        errorCode="INGESTION_FAILED" if job.status == "FAILED" else None,
        errorMessage=job.error_message,
    )


def _accepted_response(document_id: str, job_id: int, job_status: str) -> IngestionAcceptedResponse:
    """Build a consistent accepted/reused job response."""

    return IngestionAcceptedResponse(
        documentId=document_id,
        ingestionJobId=job_id,
        status=job_status,
    )
