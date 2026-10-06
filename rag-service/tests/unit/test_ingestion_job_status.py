from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from app.ingestion.repository import mark_job_status


class FakeSession:
    def __init__(self) -> None:
        self.added = []
        self.flush_count = 0

    def add(self, value) -> None:
        self.added.append(value)

    async def flush(self) -> None:
        self.flush_count += 1


@pytest.mark.asyncio
async def test_retrying_status_clears_previous_completed_timestamp() -> None:
    completed_at = datetime.now(timezone.utc)
    job = SimpleNamespace(
        status="FAILED",
        stage="failed",
        error_message="temporary provider failure",
        metadata_={},
        attempts=1,
        completed_at=completed_at,
    )
    session = FakeSession()

    await mark_job_status(
        session,
        job,
        "RETRYING",
        stage="retry_scheduled",
        error_message="429 RESOURCE_EXHAUSTED",
    )

    assert job.status == "RETRYING"
    assert job.stage == "retry_scheduled"
    assert job.completed_at is None
    assert session.added == [job]
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_terminal_status_sets_completed_timestamp() -> None:
    job = SimpleNamespace(
        status="PROCESSING",
        stage="running",
        error_message=None,
        metadata_={},
        attempts=1,
        completed_at=None,
    )
    session = FakeSession()

    await mark_job_status(
        session,
        job,
        "FAILED",
        stage="failed",
        error_message="permanent failure",
        completed=True,
    )

    assert job.status == "FAILED"
    assert job.completed_at is not None
