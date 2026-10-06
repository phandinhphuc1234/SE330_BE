import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

from llama_index.core import Document as LlamaDocument

from app.documents.object_storage import StoredObject
from app.ingestion.artifacts import (
    ARTIFACT_CHUNKS_JSONL,
    ARTIFACT_MANIFEST,
    ARTIFACT_PARSED_TEXT,
    IngestionArtifactWriter,
)
from app.ingestion.chunkers.base import Chunk
from app.ingestion.chunking import ChunkQualityReport
from app.ingestion.parsers.base import ParsedDocument


class FakeStorage:
    def __init__(self) -> None:
        self.uploads = []

    async def upload_fileobj(
        self,
        fileobj,
        *,
        object_key: str,
        content_type: str | None,
        size_bytes: int,
        sha256: str,
    ) -> StoredObject:
        body = fileobj.read()
        self.uploads.append(
            {
                "object_key": object_key,
                "content_type": content_type,
                "size_bytes": size_bytes,
                "sha256": sha256,
                "body": body,
            }
        )
        return StoredObject(
            bucket="rag-artifacts",
            object_key=object_key,
            content_type=content_type,
            size_bytes=size_bytes,
            sha256=sha256,
        )


def source_document() -> SimpleNamespace:
    return SimpleNamespace(
        id=7,
        external_document_id="doc_ebook_55",
        book_id=101,
        ebook_id=55,
        filename="original.pdf",
        storage_path="ebooks/101/55/original.pdf",
    )


async def test_ingestion_artifact_writer_uploads_artifacts_and_registers_db_records(monkeypatch) -> None:
    storage = FakeStorage()
    get_artifact = AsyncMock(return_value=None)
    create_artifact = AsyncMock()
    update_artifact = AsyncMock()
    monkeypatch.setattr("app.ingestion.artifacts.writer.get_document_artifact", get_artifact)
    monkeypatch.setattr("app.ingestion.artifacts.writer.create_document_artifact", create_artifact)
    monkeypatch.setattr("app.ingestion.artifacts.writer.update_document_artifact", update_artifact)

    parsed_documents = [
        ParsedDocument(
            text="Chương 1\nMinh bước vào thư viện.",
            metadata={"page_number": 1, "parser": "pymupdf4llm"},
        )
    ]
    cleaned_documents = [
        LlamaDocument(
            text="Chương 1\nMinh bước vào thư viện.",
            metadata={"page_number": 1, "cleaning_version": "pdf-clean-v1.0.0"},
        )
    ]
    chunks = [
        Chunk(
            text="Minh bước vào thư viện.",
            metadata={
                "chunk_index": 0,
                "vector_id": "doc-7-chunk-0-abc",
                "chunking_strategy": "library_pdf_narrative",
                "chunking_strategy_version": "v1",
            },
        )
    ]
    report = ChunkQualityReport(
        status="PASS",
        chunk_count=1,
        min_chunk_chars=24,
        max_chunk_chars=24,
        avg_chunk_chars=24,
        min_chunk_tokens=6,
        max_chunk_tokens=6,
        avg_chunk_tokens=6,
        duplicate_chunk_ratio=0,
    )

    artifact_set = await IngestionArtifactWriter(storage=storage).write_ingestion_artifacts(
        object(),
        document=source_document(),
        ingestion_job_id=12,
        source_checksum_sha256="abcdef1234567890",
        parsed_documents=parsed_documents,
        cleaned_documents=cleaned_documents,
        chunks=chunks,
        chunk_quality_report=report,
    )

    assert artifact_set.bucket == "rag-artifacts"
    assert artifact_set.version == "vabcdef12"
    assert artifact_set.prefix == "documents/doc_ebook_55/versions/vabcdef12"
    assert artifact_set.artifacts[ARTIFACT_PARSED_TEXT] == "parsed_text.txt"
    assert artifact_set.artifacts[ARTIFACT_CHUNKS_JSONL] == "chunks/chunks.jsonl"
    assert artifact_set.artifacts[ARTIFACT_MANIFEST] == "manifest.json"
    assert artifact_set.manifest_object_key == "documents/doc_ebook_55/versions/vabcdef12/manifest.json"

    uploaded_keys = {upload["object_key"] for upload in storage.uploads}
    assert "documents/doc_ebook_55/versions/vabcdef12/parsed_text.txt" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/cleaned_text.txt" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/parsed/parsed_pages.jsonl" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/cleaned/cleaned_pages.jsonl" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/processed_doc.md" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/chunks/chunks.jsonl" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/reports/chunk_quality_report.json" in uploaded_keys
    assert "documents/doc_ebook_55/versions/vabcdef12/manifest.json" in uploaded_keys

    chunks_upload = next(upload for upload in storage.uploads if upload["object_key"].endswith("chunks/chunks.jsonl"))
    chunks_payload = json.loads(chunks_upload["body"].decode("utf-8").strip())
    assert chunks_payload["text"] == "Minh bước vào thư viện."
    assert chunks_payload["vector_id"] == "doc-7-chunk-0-abc"

    manifest_upload = next(upload for upload in storage.uploads if upload["object_key"].endswith("manifest.json"))
    manifest = json.loads(manifest_upload["body"].decode("utf-8"))
    assert manifest["documentId"] == "doc_ebook_55"
    assert manifest["source"]["checksum_sha256"] == "abcdef1234567890"
    assert manifest["artifacts"][ARTIFACT_CHUNKS_JSONL] == "chunks/chunks.jsonl"

    assert get_artifact.await_count == 8
    assert create_artifact.await_count == 8
    update_artifact.assert_not_awaited()
