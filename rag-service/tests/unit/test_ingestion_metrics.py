from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.ingestion_metrics import get_ingestion_metrics


@pytest.mark.asyncio
async def test_ingestion_metrics_reads_aggregate_across_worker_jobs(monkeypatch):
    rows = [{"status": "INDEXED", "job_count": 3, "mean_duration_seconds": 70.5}]
    session = AsyncMock()
    session.execute.return_value = SimpleNamespace(mappings=lambda: rows)
    context = AsyncMock()
    context.__aenter__.return_value = session
    monkeypatch.setattr("app.core.ingestion_metrics.async_session_factory", lambda: context)

    assert await get_ingestion_metrics() == rows
    session.execute.assert_awaited_once()


@pytest.mark.asyncio
async def test_ingestion_metrics_returns_unavailable_without_exposing_db_error(monkeypatch):
    context = AsyncMock()
    context.__aenter__.side_effect = RuntimeError("db-credentials-must-not-be-logged")
    monkeypatch.setattr("app.core.ingestion_metrics.async_session_factory", lambda: context)
    logger = SimpleNamespace(warning=lambda *args, **kwargs: captured.append((args, kwargs)))
    captured = []
    monkeypatch.setattr("app.core.ingestion_metrics.logger", logger)

    assert await get_ingestion_metrics() is None
    assert "db-credentials-must-not-be-logged" not in str(captured)
    assert captured[0][1]["error_type"] == "RuntimeError"
