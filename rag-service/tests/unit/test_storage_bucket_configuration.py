from io import BytesIO
from types import SimpleNamespace
from unittest.mock import Mock

from botocore.exceptions import ClientError
import pytest

from app.core.config import Settings
from app.core.exceptions import IngestionError
from app.documents.object_storage import S3CompatibleStorageAdapter


def storage_settings() -> SimpleNamespace:
    return SimpleNamespace(
        rag_artifact_bucket="rag-artifacts",
        rag_source_bucket="library-private",
        object_storage_endpoint="http://seaweedfs:8333",
        object_storage_region="us-east-1",
        object_storage_access_key="admin",
        object_storage_secret_key="secret",
    )


def test_bucket_defaults_define_three_real_buckets_and_legacy_alias() -> None:
    settings = Settings(_env_file=None)

    assert settings.storage_backend == "s3"
    assert settings.library_ebook_bucket == "library-private"
    assert settings.library_temp_bucket == "library-temp"
    assert settings.rag_artifact_bucket == "rag-artifacts"
    assert settings.rag_source_bucket == settings.library_ebook_bucket


async def test_s3_adapter_writes_artifacts_and_reads_recorded_source_bucket(
    monkeypatch,
    tmp_path,
) -> None:
    monkeypatch.setattr(
        "app.documents.object_storage.get_settings",
        storage_settings,
    )
    adapter = S3CompatibleStorageAdapter()
    client = Mock()
    adapter._client = client
    adapter._bucket_ready = True

    await adapter.upload_fileobj(
        BytesIO(b"parsed text"),
        object_key="documents/doc-1/parsed_text.txt",
        content_type="text/plain",
        size_bytes=11,
        sha256="a" * 64,
    )
    client.upload_fileobj.assert_called_once()
    assert client.upload_fileobj.call_args.args[1] == "rag-artifacts"

    destination = tmp_path / "original.pdf"
    await adapter.download_to_path(
        "ebooks/101/55/original.pdf",
        destination,
        bucket="library-private",
    )
    client.download_file.assert_called_once_with(
        "library-private",
        "ebooks/101/55/original.pdf",
        str(destination),
    )


async def test_s3_adapter_heads_recorded_source_bucket(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.documents.object_storage.get_settings",
        storage_settings,
    )
    adapter = S3CompatibleStorageAdapter()
    client = Mock()
    client.head_object.return_value = {
        "ContentLength": 42,
        "ContentType": "application/pdf",
        "Metadata": {"sha256": "a" * 64},
    }
    adapter._client = client

    metadata = await adapter.head_object(
        "ebooks/101/55/original.pdf",
        bucket="library-private",
    )

    client.head_object.assert_called_once_with(
        Bucket="library-private",
        Key="ebooks/101/55/original.pdf",
    )
    assert metadata.bucket == "library-private"
    assert metadata.object_key == "ebooks/101/55/original.pdf"
    assert metadata.content_length == 42
    assert metadata.content_type == "application/pdf"
    assert metadata.metadata == {"sha256": "a" * 64}


async def test_s3_adapter_head_missing_source_object_raises_clear_error(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.documents.object_storage.get_settings",
        storage_settings,
    )
    adapter = S3CompatibleStorageAdapter()
    client = Mock()
    client.head_object.side_effect = ClientError(
        {
            "Error": {
                "Code": "404",
                "Message": "Not Found",
            }
        },
        "HeadObject",
    )
    adapter._client = client

    with pytest.raises(IngestionError) as exc_info:
        await adapter.head_object(
            "ebooks/101/55/original.pdf",
            bucket="library-private",
        )

    assert exc_info.value.error_code == "SOURCE_OBJECT_NOT_FOUND"
