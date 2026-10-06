from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.ingestion.models import IngestionJob


async def create_ingestion_job(
    session: AsyncSession,
    document_id: int,
    metadata: dict | None = None,
) -> IngestionJob:
    job = IngestionJob(
        document_id=document_id,
        status="QUEUED",
        stage="queued",
        attempts=0,
        metadata_=metadata or {},
    )
    session.add(job)
    await session.flush()
    return job


async def get_ingestion_job(session: AsyncSession, job_id: int) -> IngestionJob | None:
    result = await session.execute(select(IngestionJob).where(IngestionJob.id == job_id))
    return result.scalar_one_or_none()


async def get_latest_ingestion_job_for_document(
    session: AsyncSession,
    document_id: int,
) -> IngestionJob | None:
    result = await session.execute(
        select(IngestionJob)
        .where(IngestionJob.document_id == document_id)
        .order_by(IngestionJob.id.desc())
        .limit(1)
    )
    return result.scalar_one_or_none()


async def attach_task_id(session: AsyncSession, job: IngestionJob, task_id: str) -> IngestionJob:
    job.task_id = task_id
    session.add(job)
    await session.flush()
    return job


async def mark_job_status(
    session: AsyncSession,
    job: IngestionJob,
    status: str,
    *,
    stage: str | None = None,
    error_message: str | None = None,
    metadata: dict | None = None,
    increment_attempts: bool = False,
    completed: bool = False,
) -> IngestionJob:
    job.status = status
    # A task may be marked FAILED by the pipeline and then moved to RETRYING by
    # Celery. Active states must not retain the terminal timestamp from the
    # previous attempt, otherwise polling clients see contradictory state.
    terminal_statuses = {"FAILED", "INDEXED", "COMPLETED", "CANCELLED"}
    if status not in terminal_statuses:
        job.completed_at = None
    if stage is not None:
        job.stage = stage
    job.error_message = error_message
    if metadata:
        job.metadata_ = {**(job.metadata_ or {}), **metadata}
    if increment_attempts:
        job.attempts += 1
    if completed:
        job.completed_at = datetime.now(timezone.utc)
    session.add(job)
    await session.flush()
    return job
