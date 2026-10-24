"""Read a bounded status aggregate shared by all ingestion worker processes."""

import asyncio

from sqlalchemy import text

from app.core.logger import get_logger
from app.db.session import async_session_factory


logger = get_logger(__name__)
_SNAPSHOT_SQL = text("""
    SELECT CASE WHEN status IN (
        'QUEUED', 'PROCESSING', 'PARSED', 'CHUNKED', 'EMBEDDING', 'INDEXING',
        'INDEXED', 'COMPLETED', 'FAILED', 'RETRYING', 'CANCELLED'
    ) THEN status ELSE 'OTHER' END AS status,
    COUNT(*) AS job_count,
    AVG(GREATEST(EXTRACT(EPOCH FROM (completed_at - created_at)), 0))
        FILTER (WHERE completed_at IS NOT NULL) AS mean_duration_seconds
    FROM ingestion_jobs
    GROUP BY 1
""")


async def get_ingestion_metrics() -> list[dict] | None:
    """A DB outage must not hide the API/provider metrics from Prometheus."""

    try:
        async with asyncio.timeout(2):
            async with async_session_factory() as session:
                result = await session.execute(_SNAPSHOT_SQL)
                return [dict(row) for row in result.mappings()]
    except Exception as error:
        logger.warning("ingestion_metrics_snapshot_failed", error_type=type(error).__name__)
        return None
