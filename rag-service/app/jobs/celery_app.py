from celery import Celery

from app.core.config import get_settings
from app.core.logger import configure_logging
from app.core.logger import get_logger
from app.jobs.beat_schedule import beat_schedule

configure_logging()
settings = get_settings()
logger = get_logger(__name__)

celery_app = Celery(
    "professional_rag_platform",
    broker=settings.effective_celery_broker_url,
    backend=settings.effective_celery_result_backend,
    include=[
        "app.jobs.ingestion_jobs",
        "app.jobs.cleanup_jobs",
    ],
)
celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    timezone="Asia/Bangkok",
    beat_schedule=beat_schedule,
    # PDF ingestion can be slow; ack only after completion so lost workers requeue work.
    task_acks_late=True,
    task_reject_on_worker_lost=True,
    # Avoid one worker reserving many long-running ingestion tasks at once.
    worker_prefetch_multiplier=1,
)

logger.info(
    "celery_app_configured",
    broker_url=settings.effective_celery_broker_url,
    result_backend=settings.effective_celery_result_backend,
    timezone="Asia/Bangkok",
    worker_prefetch_multiplier=1,
    task_acks_late=True,
    qdrant_url=settings.qdrant_url,
    qdrant_collection=settings.qdrant_collection_name,
    embedding_provider=settings.embedding_provider,
    embedding_model=settings.embedding_model,
    embedding_dim=settings.embedding_dim,
    embedding_version=settings.embedding_version,
    embedding_batch_size=settings.embedding_batch_size,
    embedding_max_retries=settings.embedding_max_retries,
    embedding_retry_base_delay_seconds=settings.embedding_retry_base_delay_seconds,
    embedding_retry_max_delay_seconds=settings.embedding_retry_max_delay_seconds,
)
