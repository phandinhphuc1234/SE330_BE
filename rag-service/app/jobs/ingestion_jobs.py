import asyncio
from time import perf_counter

import structlog.contextvars
from celery.exceptions import MaxRetriesExceededError

from app.core.logger import get_logger
from app.db.session import async_session_factory
from app.ingestion.pipeline import IngestionPipeline
from app.ingestion.repository import get_ingestion_job, mark_job_status
from app.jobs.celery_app import celery_app

logger = get_logger(__name__)


async def _mark_retrying(ingestion_job_id: int, error: str) -> None:
    async with async_session_factory() as session:
        job = await get_ingestion_job(session, ingestion_job_id)
        if job is not None:
            await mark_job_status(
                session,
                job,
                "RETRYING",
                stage="retry_scheduled",
                error_message=error,
            )
            await session.commit()


@celery_app.task(
    bind=True,
    name="app.jobs.ingestion_jobs.process_document",
    max_retries=3,
)
def process_document(self, document_id: int, ingestion_job_id: int | None = None) -> dict:
    started_at = perf_counter()
    structlog.contextvars.bind_contextvars(
        task_id=self.request.id,
        document_id=document_id,
        ingestion_job_id=ingestion_job_id,
    )
    try:
        logger.info(
            "ingestion_task_started",
            retry_count=self.request.retries,
            max_retries=self.max_retries,
            queue=getattr(self.request, "delivery_info", {}).get("routing_key"),
        )
        result = asyncio.run(IngestionPipeline().run(document_id, ingestion_job_id))
        payload = {
            "document_id": result.document_id,
            "ingestion_job_id": result.job_id,
            "page_count": result.page_count,
            "chunk_count": result.chunk_count,
            "status": result.status,
        }
        logger.info(
            "ingestion_task_succeeded",
            page_count=result.page_count,
            chunk_count=result.chunk_count,
            status=result.status,
            duration_ms=round((perf_counter() - started_at) * 1000, 2),
        )
        return payload
    except Exception as exc:
        countdown = min(60, 2**self.request.retries * 5)
        if ingestion_job_id is not None and self.request.retries < self.max_retries:
            logger.warning(
                "ingestion_task_retrying",
                retry_count=self.request.retries,
                max_retries=self.max_retries,
                next_retry_seconds=countdown,
                error=str(exc),
                duration_ms=round((perf_counter() - started_at) * 1000, 2),
            )
            # Persist retry state before Celery reschedules the task.
            asyncio.run(_mark_retrying(ingestion_job_id, str(exc)))
        try:
            # Exponential backoff keeps provider/parser failures from retrying in a tight loop.
            raise self.retry(exc=exc, countdown=countdown)
        except MaxRetriesExceededError:
            logger.error(
                "ingestion_task_failed",
                retry_count=self.request.retries,
                max_retries=self.max_retries,
                error=str(exc),
                duration_ms=round((perf_counter() - started_at) * 1000, 2),
                exc_info=True,
            )
            raise exc
    finally:
        structlog.contextvars.clear_contextvars()
