import hashlib
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from app.core.exceptions import IngestionError
from app.ingestion.pipeline import IngestionPipeline


FAKE_PDF_BYTES = b"%PDF-1.7\n"
FAKE_PDF_SHA256 = hashlib.sha256(FAKE_PDF_BYTES).hexdigest()


class FakeStorage:
    def __init__(self, content_length: int, *, fail_download: bool = False) -> None:
        self.content_length = content_length
        self.fail_download = fail_download
        self.download_called = False
        self.download_parent = None

    async def head_object(self, object_key: str, *, bucket: str | None = None):
        return SimpleNamespace(
            bucket=bucket,
            object_key=object_key,
            content_length=self.content_length,
            content_type="application/pdf",
            metadata={},
        )

    async def download_to_path(self, object_key: str, destination_path, *, bucket: str | None = None):
        self.download_called = True
        self.download_parent = destination_path.parent
        if self.fail_download:
            raise IngestionError("download failed", error_code="SOURCE_DOWNLOAD_FAILED")
        destination_path.write_bytes(FAKE_PDF_BYTES)


def source_document() -> SimpleNamespace:
    return SimpleNamespace(
        id=7,
        filename="original.pdf",
        metadata_={"storage_backend": "s3"},
        storage_path="ebooks/101/55/original.pdf",
    )


def raw_artifact(
    size_bytes: int | None = len(FAKE_PDF_BYTES),
    checksum_sha256: str | None = FAKE_PDF_SHA256,
) -> SimpleNamespace:
    return SimpleNamespace(
        bucket="library-private",
        object_key="ebooks/101/55/original.pdf",
        size_bytes=size_bytes,
        checksum_sha256=checksum_sha256,
    )


def write_pdf(path, *, pages: int = 1, encrypted: bool = False) -> None:
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(width=72, height=72)
    if encrypted:
        writer.encrypt("secret")
    with path.open("wb") as output_file:
        writer.write(output_file)


def write_text_pdf(path, text: str | None = None) -> None:
    text = text or (
        "This is a searchable PDF text layer with enough characters for validation. "
        "The RAG worker should accept this file because OCR is not required."
    )
    writer = PdfWriter()
    page = writer.add_blank_page(width=612, height=792)
    font = DictionaryObject(
        {
            NameObject("/Type"): NameObject("/Font"),
            NameObject("/Subtype"): NameObject("/Type1"),
            NameObject("/BaseFont"): NameObject("/Helvetica"),
        }
    )
    font_ref = writer._add_object(font)
    page[NameObject("/Resources")] = DictionaryObject(
        {
            NameObject("/Font"): DictionaryObject(
                {
                    NameObject("/F1"): font_ref,
                }
            )
        }
    )
    content_stream = DecodedStreamObject()
    content_stream.set_data(f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode())
    page[NameObject("/Contents")] = writer._add_object(content_stream)

    with path.open("wb") as output_file:
        writer.write(output_file)


async def test_prepare_input_file_heads_source_before_download(monkeypatch) -> None:
    artifact = raw_artifact()
    storage = FakeStorage(content_length=len(FAKE_PDF_BYTES))
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_object_storage_adapter",
        lambda: storage,
    )

    file_path, temporary_directory = await IngestionPipeline()._prepare_input_file(
        object(),
        source_document(),
    )

    try:
        assert storage.download_called is True
        assert file_path.exists()
        assert file_path.read_bytes().startswith(b"%PDF-")
        assert file_path.parent.name.startswith("rag-document-7-")
    finally:
        temporary_directory.cleanup()


async def test_prepare_input_file_rejects_source_size_mismatch_before_download(monkeypatch) -> None:
    artifact = raw_artifact(size_bytes=len(FAKE_PDF_BYTES))
    storage = FakeStorage(content_length=len(FAKE_PDF_BYTES) - 1)
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_object_storage_adapter",
        lambda: storage,
    )

    with pytest.raises(IngestionError) as exc_info:
        await IngestionPipeline()._prepare_input_file(
            object(),
            source_document(),
        )

    assert exc_info.value.error_code == "SOURCE_SIZE_MISMATCH"
    assert storage.download_called is False


async def test_prepare_input_file_cleans_temp_directory_when_download_fails(monkeypatch) -> None:
    artifact = raw_artifact()
    storage = FakeStorage(content_length=len(FAKE_PDF_BYTES), fail_download=True)
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_object_storage_adapter",
        lambda: storage,
    )

    with pytest.raises(IngestionError) as exc_info:
        await IngestionPipeline()._prepare_input_file(
            object(),
            source_document(),
        )

    assert exc_info.value.error_code == "SOURCE_DOWNLOAD_FAILED"
    assert storage.download_called is True
    assert storage.download_parent is not None
    assert not storage.download_parent.exists()


async def test_prepare_input_file_rejects_checksum_mismatch_and_cleans_temp(monkeypatch) -> None:
    artifact = raw_artifact(checksum_sha256="0" * 64)
    storage = FakeStorage(content_length=len(FAKE_PDF_BYTES))
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_object_storage_adapter",
        lambda: storage,
    )

    with pytest.raises(IngestionError) as exc_info:
        await IngestionPipeline()._prepare_input_file(
            object(),
            source_document(),
        )

    assert exc_info.value.error_code == "PDF_CHECKSUM_MISMATCH"
    assert storage.download_called is True
    assert storage.download_parent is not None
    assert not storage.download_parent.exists()


async def test_prepare_input_file_prefers_job_source_snapshot_over_mutable_artifact(monkeypatch) -> None:
    artifact = raw_artifact(
        size_bytes=999,
        checksum_sha256="0" * 64,
    )
    storage = FakeStorage(content_length=len(FAKE_PDF_BYTES))
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_document_artifact",
        AsyncMock(return_value=artifact),
    )
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_object_storage_adapter",
        lambda: storage,
    )

    file_path, temporary_directory = await IngestionPipeline()._prepare_input_file(
        object(),
        source_document(),
        source_snapshot={
            "bucket": "library-private",
            "object_key": "ebooks/101/55/original.pdf",
            "file_size_bytes": len(FAKE_PDF_BYTES),
            "checksum_sha256": FAKE_PDF_SHA256,
        },
    )

    try:
        assert file_path.read_bytes() == FAKE_PDF_BYTES
    finally:
        temporary_directory.cleanup()


def test_validate_input_file_accepts_parseable_pdf(tmp_path) -> None:
    file_path = tmp_path / "valid.pdf"
    write_text_pdf(file_path)

    IngestionPipeline()._validate_input_file(file_path)


def test_validate_input_file_rejects_fake_pdf_magic_bytes(tmp_path) -> None:
    file_path = tmp_path / "fake.pdf"
    file_path.write_bytes(b"this is not actually a pdf")

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_input_file(file_path)

    assert exc_info.value.error_code == "PDF_MAGIC_BYTES_INVALID"


def test_validate_pdf_rejects_persisted_aws_chunked_framing(tmp_path) -> None:
    file_path = tmp_path / "aws-chunked.pdf"
    file_path.write_bytes(
        b"20000;chunk-signature=abc123\r\n"
        b"%PDF-1.7\n"
        b"this payload must be rejected before structural parsing"
    )

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_pdf_magic_bytes(file_path)

    assert exc_info.value.error_code == "PDF_AWS_CHUNKED_FRAMING_DETECTED"


def test_validate_input_file_rejects_corrupted_pdf_structure(tmp_path) -> None:
    file_path = tmp_path / "corrupted.pdf"
    file_path.write_bytes(b"%PDF-1.7\nthis header is real-ish but the pdf structure is broken")

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_input_file(file_path)

    assert exc_info.value.error_code == "PDF_STRUCTURE_INVALID"


def test_validate_input_file_rejects_encrypted_pdf(tmp_path) -> None:
    file_path = tmp_path / "encrypted.pdf"
    write_pdf(file_path, encrypted=True)

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_input_file(file_path)

    assert exc_info.value.error_code == "PDF_ENCRYPTED"


def test_validate_input_file_rejects_empty_pdf(tmp_path) -> None:
    file_path = tmp_path / "empty.pdf"
    write_pdf(file_path, pages=0)

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_input_file(file_path)

    assert exc_info.value.error_code == "PDF_PAGE_COUNT_INVALID"


def test_validate_input_file_rejects_pdf_over_page_limit(tmp_path, monkeypatch) -> None:
    file_path = tmp_path / "too-many-pages.pdf"
    write_pdf(file_path, pages=2)
    monkeypatch.setattr(
        "app.ingestion.pipeline.get_settings",
        lambda: SimpleNamespace(
            ingestion_allowed_extensions=[".pdf"],
            max_pdf_pages=1,
        ),
    )

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_input_file(file_path)

    assert exc_info.value.error_code == "PDF_PAGE_LIMIT_EXCEEDED"


def test_validate_input_file_rejects_pdf_without_text_layer(tmp_path) -> None:
    file_path = tmp_path / "scanned-like.pdf"
    write_pdf(file_path)

    with pytest.raises(IngestionError) as exc_info:
        IngestionPipeline()._validate_input_file(file_path)

    assert exc_info.value.error_code == "PDF_OCR_REQUIRED"
