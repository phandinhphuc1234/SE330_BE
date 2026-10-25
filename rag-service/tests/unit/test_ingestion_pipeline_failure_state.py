from types import SimpleNamespace
from unittest.mock import AsyncMock, call

import pytest

from app.core.exceptions import IngestionError
from app.ingestion.pipeline import IngestionPipeline


class ExpiringJob:
    metadata_ = {}

    def __init__(self):
        self.expired = False

    @property
    def id(self):
        if self.expired:
            raise RuntimeError("Implicit ORM access after rollback is forbidden")
        return 9


class FailureSession:
    def __init__(self, job):
        self.job = job
        self.commit = AsyncMock()
        self.rollback = AsyncMock(side_effect=self.expire_job)

    def expire_job(self):
        self.job.expired = True

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None


@pytest.mark.asyncio
@pytest.mark.parametrize("job_still_exists", [True, False])
async def test_failure_reloads_job_by_scalar_id_after_rollback(monkeypatch, job_still_exists):
    job = ExpiringJob()
    session = FailureSession(job)
    failed_job = SimpleNamespace(id=9) if job_still_exists else None
    get_job = AsyncMock(side_effect=[job, failed_job])
    mark_status = AsyncMock()
    document = SimpleNamespace(
        id=7, external_document_id="readiness-fixture", source_type="LIBRARY_EBOOK",
        source_id="ebook:55", book_id=101, ebook_id=55, filename="empty.pdf",
        storage_path="ebooks/101/55/original.pdf", metadata_={},
    )
    monkeypatch.setattr("app.ingestion.pipeline.async_session_factory", lambda: session)
    monkeypatch.setattr("app.ingestion.pipeline.get_ingestion_job", get_job)
    monkeypatch.setattr("app.ingestion.pipeline.mark_job_status", mark_status)
    monkeypatch.setattr("app.ingestion.pipeline.get_document", AsyncMock(return_value=document))
    pipeline = IngestionPipeline()
    original_error = IngestionError("PDF has no pages.", error_code="PDF_PAGE_COUNT_INVALID")
    monkeypatch.setattr(pipeline, "_prepare_input_file", AsyncMock(side_effect=original_error))

    with pytest.raises(IngestionError) as raised:
        await pipeline.run(document_id=7, ingestion_job_id=9)

    assert raised.value is original_error
    assert job.expired
    assert get_job.await_args_list == [call(session, 9), call(session, 9)]
    session.rollback.assert_awaited_once()
    if job_still_exists:
        assert mark_status.await_args_list[-1] == call(
            session, failed_job, "FAILED", stage="failed",
            error_message="PDF has no pages.", completed=True,
        )
        assert session.commit.await_count == 2
    else:
        assert mark_status.await_count == 1
        assert session.commit.await_count == 1
