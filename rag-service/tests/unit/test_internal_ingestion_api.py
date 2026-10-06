from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.api.internal.routes_ingestions import (
    LibraryEbookIngestionRequest,
    create_library_ebook_ingestion,
)
from app.core.exceptions import UnauthorizedError
from app.core.internal_auth import require_internal_api_key


def library_payload() -> LibraryEbookIngestionRequest:
    return LibraryEbookIngestionRequest.model_validate(
        {
            "sourceType": "LIBRARY_EBOOK",
            "bookId": 101,
            "ebookId": 55,
            "bucket": "library-private",
            "objectKey": "ebooks/101/55/original.pdf",
            "checksumSha256": "a" * 64,
            "contentType": "application/pdf",
            "fileSizeBytes": 12345,
        }
    )


async def test_internal_api_key_uses_constant_service_credential(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.core.internal_auth.get_settings",
        lambda: SimpleNamespace(rag_internal_api_key="service-secret"),
    )

    await require_internal_api_key("service-secret")
    with pytest.raises(UnauthorizedError) as exc_info:
        await require_internal_api_key("wrong-secret")

    assert exc_info.value.error_code == "INVALID_INTERNAL_API_KEY"


async def test_library_ingestion_creates_job_and_enqueues_task(monkeypatch) -> None:
    document = SimpleNamespace(
        id=7,
        external_document_id="doc_ebook_55",
        source_type="LIBRARY_EBOOK",
        source_id="ebook:55",
        book_id=101,
        ebook_id=55,
        filename="original.pdf",
        storage_path="ebooks/101/55/original.pdf",
        metadata_={},
    )
    job = SimpleNamespace(id=9, status="QUEUED")
    session = SimpleNamespace(commit=AsyncMock())
    delay = Mock(return_value=SimpleNamespace(id="celery-task-1"))

    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_settings",
        lambda: SimpleNamespace(library_ebook_bucket="library-private"),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_document_by_source",
        AsyncMock(return_value=None),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.create_document",
        AsyncMock(return_value=document),
    )
    create_artifact = AsyncMock()
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.create_document_artifact",
        create_artifact,
    )
    create_job = AsyncMock(return_value=job)
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.create_ingestion_job",
        create_job,
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.attach_task_id",
        AsyncMock(return_value=job),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.process_document",
        SimpleNamespace(delay=delay),
    )

    response = await create_library_ebook_ingestion(library_payload(), session)

    assert response.model_dump(by_alias=True) == {
        "documentId": "doc_ebook_55",
        "ingestionJobId": 9,
        "status": "QUEUED",
    }
    assert create_artifact.await_args.kwargs["bucket"] == "library-private"
    assert create_artifact.await_args.kwargs["object_key"] == "ebooks/101/55/original.pdf"
    assert create_artifact.await_args.kwargs["artifact_type"] == "RAW_ORIGINAL"
    assert create_job.await_args.kwargs["metadata"]["file_size_bytes"] == 12345
    assert create_job.await_args.kwargs["metadata"]["content_type"] == "application/pdf"
    delay.assert_called_once_with(7, 9)
    assert session.commit.await_count == 2


async def test_same_checksum_returns_existing_job_without_reenqueue(monkeypatch) -> None:
    document = SimpleNamespace(id=7, external_document_id="doc_ebook_55")
    artifact = SimpleNamespace(checksum_sha256="a" * 64)
    job = SimpleNamespace(id=9, status="PROCESSING")
    session = SimpleNamespace(commit=AsyncMock())
    delay = Mock()

    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_settings",
        lambda: SimpleNamespace(library_ebook_bucket="library-private"),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_document_by_source",
        AsyncMock(return_value=document),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_latest_ingestion_job_for_document",
        AsyncMock(return_value=job),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.process_document",
        SimpleNamespace(delay=delay),
    )

    response = await create_library_ebook_ingestion(library_payload(), session)

    assert response.status == "PROCESSING"
    delay.assert_not_called()
    session.commit.assert_not_awaited()


async def test_force_reindex_creates_new_job_for_same_checksum(monkeypatch) -> None:
    document = SimpleNamespace(
        id=7,
        external_document_id="doc_ebook_55",
        source_type="LIBRARY_EBOOK",
        source_id="ebook:55",
        book_id=101,
        ebook_id=55,
        filename="original.pdf",
        storage_path="ebooks/101/55/original.pdf",
        metadata_={},
    )
    artifact = SimpleNamespace(checksum_sha256="a" * 64)
    existing_job = SimpleNamespace(id=9, status="INDEXED")
    new_job = SimpleNamespace(id=10, status="QUEUED")
    session = SimpleNamespace(commit=AsyncMock())
    delay = Mock(return_value=SimpleNamespace(id="celery-task-2"))

    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_settings",
        lambda: SimpleNamespace(library_ebook_bucket="library-private"),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_document_by_source",
        AsyncMock(return_value=document),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.get_latest_ingestion_job_for_document",
        AsyncMock(return_value=existing_job),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.update_document_artifact",
        AsyncMock(),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.create_ingestion_job",
        AsyncMock(return_value=new_job),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.attach_task_id",
        AsyncMock(return_value=new_job),
    )
    monkeypatch.setattr(
        "app.api.internal.routes_ingestions.process_document",
        SimpleNamespace(delay=delay),
    )

    payload = library_payload().model_copy(update={"force_reindex": True})
    response = await create_library_ebook_ingestion(payload, session)

    assert response.ingestion_job_id == 10
    assert response.status == "QUEUED"
    delay.assert_called_once_with(7, 10)
    assert session.commit.await_count == 2
