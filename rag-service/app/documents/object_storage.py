from abc import ABC, abstractmethod
import asyncio
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from app.core.config import get_settings
from app.core.exceptions import IngestionError, ValidationError

# Đây là module định nghĩa các lớp và giao diện liên quan đến 
# lưu trữ đối tượng (object storage) cho ứng dụng.
@dataclass(frozen=True)
class StoredObject:
    bucket: str
    object_key: str
    content_type: str | None
    size_bytes: int
    sha256: str


@dataclass(frozen=True)
class ObjectMetadata:
    """Small S3 HEAD result used to validate a source object before downloading it."""

    bucket: str
    object_key: str
    content_length: int
    content_type: str | None
    metadata: dict[str, str]

# ObjectStorageAdapter là một giao diện trừu tượng định nghĩa các
#  phương thức cần thiết để 
# tương tác với hệ thống lưu trữ đối tượng,

class ObjectStorageAdapter(ABC):
    """Storage contract for durable ingestion artifacts."""

    @abstractmethod
    async def upload_fileobj(
        self,
        fileobj: BinaryIO,
        *,
        object_key: str,
        content_type: str | None,
        size_bytes: int,
        sha256: str,
    ) -> StoredObject:
        raise NotImplementedError

    @abstractmethod
    async def head_object(self, object_key: str, *, bucket: str | None = None) -> ObjectMetadata:
        """Read object metadata without downloading the object body."""

        raise NotImplementedError

    @abstractmethod
    async def download_to_path(
        self,
        object_key: str,
        destination_path: Path,
        *,
        bucket: str | None = None,
    ) -> None:
        raise NotImplementedError

    @abstractmethod
    async def delete_object(self, object_key: str, *, bucket: str | None = None) -> None:
        raise NotImplementedError


class S3CompatibleStorageAdapter(ObjectStorageAdapter):
    """S3-compatible adapter for SeaweedFS, MinIO, Ceph, Garage, or managed S3."""

    def __init__(self) -> None:
        settings = get_settings()
        # RAG writes its own derived files to rag-artifacts. Source downloads normally
        # pass the bucket recorded on DocumentArtifact; source_bucket is only a legacy
        # fallback for old rows that stored a key without a bucket.
        self.bucket = settings.rag_artifact_bucket
        self.source_bucket = settings.rag_source_bucket
        self.endpoint_url = settings.object_storage_endpoint
        self.region_name = settings.object_storage_region
        self.access_key = settings.object_storage_access_key
        self.secret_key = settings.object_storage_secret_key
        self._client = None
        self._bucket_ready = False

    @property
    def client(self):
        if self._client is None:
            try:
                import boto3
                from botocore.client import Config
            except ImportError as exc:
                raise IngestionError(
                    "boto3 is required for S3-compatible object storage",
                    error_code="OBJECT_STORAGE_CLIENT_MISSING",
                ) from exc

            self._client = boto3.client(
                "s3",
                endpoint_url=self.endpoint_url,
                aws_access_key_id=self.access_key,
                aws_secret_access_key=self.secret_key,
                region_name=self.region_name,
                config=Config(
                    signature_version="s3v4",
                    s3={"addressing_style": "path"},
                    retries={"max_attempts": 4, "mode": "standard"},
                ),
            )
        return self._client

    async def upload_fileobj(
        self,
        fileobj: BinaryIO,
        *,
        object_key: str,
        content_type: str | None,
        size_bytes: int,
        sha256: str,
    ) -> StoredObject:
        await asyncio.to_thread(self._ensure_bucket)
        fileobj.seek(0)
        extra_args = {"Metadata": {"sha256": sha256, "size_bytes": str(size_bytes)}}
        if content_type:
            extra_args["ContentType"] = content_type

        await asyncio.to_thread(
            self.client.upload_fileobj,
            fileobj,
            self.bucket,
            object_key,
            ExtraArgs=extra_args,
        )
        return StoredObject(
            bucket=self.bucket,
            object_key=object_key,
            content_type=content_type,
            size_bytes=size_bytes,
            sha256=sha256,
        )

    async def head_object(self, object_key: str, *, bucket: str | None = None) -> ObjectMetadata:
        resolved_bucket = bucket or self.source_bucket
        try:
            # HEAD is the cheapest validation step: it proves the object exists and
            # gives us size/content metadata without pulling the PDF through the network.
            response = await asyncio.to_thread(
                self.client.head_object,
                Bucket=resolved_bucket,
                Key=object_key,
            )
        except Exception as exc:
            if _is_missing_object_error(exc):
                raise IngestionError(
                    f"Source object was not found: s3://{resolved_bucket}/{object_key}",
                    error_code="SOURCE_OBJECT_NOT_FOUND",
                ) from exc
            raise

        return ObjectMetadata(
            bucket=resolved_bucket,
            object_key=object_key,
            content_length=int(response.get("ContentLength") or 0),
            content_type=response.get("ContentType"),
            metadata=dict(response.get("Metadata") or {}),
        )

    async def download_to_path(
        self,
        object_key: str,
        destination_path: Path,
        *,
        bucket: str | None = None,
    ) -> None:
        # Parsers/checksum validators need a stable seekable local file. The
        # caller owns the temporary destination and removes it after the task.
        destination_path.parent.mkdir(parents=True, exist_ok=True)
        await asyncio.to_thread(
            self.client.download_file,
            bucket or self.source_bucket,
            object_key,
            str(destination_path),
        )

    async def delete_object(self, object_key: str, *, bucket: str | None = None) -> None:
        await asyncio.to_thread(
            self.client.delete_object,
            Bucket=bucket or self.bucket,
            Key=object_key,
        )

    def _ensure_bucket(self) -> None:
        if self._bucket_ready:
            return

        try:
            self.client.head_bucket(Bucket=self.bucket)
        except Exception as exc:
            if not _is_missing_bucket_error(exc):
                raise
            self.client.create_bucket(Bucket=self.bucket)

        self._bucket_ready = True


class LocalStorageAdapter(ObjectStorageAdapter):
    """Local filesystem adapter kept only as a development fallback."""

    def __init__(self) -> None:
        self.root = Path(get_settings().storage_local_path)

    async def upload_fileobj(
        self,
        fileobj: BinaryIO,
        *,
        object_key: str,
        content_type: str | None,
        size_bytes: int,
        sha256: str,
    ) -> StoredObject:
        destination = self.root / object_key
        destination.parent.mkdir(parents=True, exist_ok=True)
        fileobj.seek(0)
        with destination.open("wb") as output:
            while chunk := fileobj.read(1024 * 1024):
                output.write(chunk)

        return StoredObject(
            bucket="local",
            object_key=str(destination),
            content_type=content_type,
            size_bytes=size_bytes,
            sha256=sha256,
        )

    async def head_object(self, object_key: str, *, bucket: str | None = None) -> ObjectMetadata:
        source_path = Path(object_key)
        if not source_path.exists():
            raise IngestionError(
                f"Stored file was not found: {source_path}",
                error_code="SOURCE_OBJECT_NOT_FOUND",
            )
        return ObjectMetadata(
            bucket=bucket or "local",
            object_key=object_key,
            content_length=source_path.stat().st_size,
            content_type=None,
            metadata={},
        )

    async def download_to_path(
        self,
        object_key: str,
        destination_path: Path,
        *,
        bucket: str | None = None,
    ) -> None:
        source_path = Path(object_key)
        if not source_path.exists():
            raise IngestionError(
                f"Stored file was not found: {source_path}",
                error_code="STORED_FILE_NOT_FOUND",
            )
        destination_path.parent.mkdir(parents=True, exist_ok=True)
        destination_path.write_bytes(source_path.read_bytes())

    async def delete_object(self, object_key: str, *, bucket: str | None = None) -> None:
        Path(object_key).unlink(missing_ok=True)


def get_object_storage_adapter() -> ObjectStorageAdapter:
    backend = get_settings().storage_backend.lower()
    if backend in {"s3", "s3-compatible", "seaweedfs", "minio"}:
        return S3CompatibleStorageAdapter()
    if backend == "local":
        return LocalStorageAdapter()
    raise ValidationError(
        f"Unsupported storage backend: {backend}",
        error_code="UNSUPPORTED_STORAGE_BACKEND",
    )

# Hàm _is_missing_bucket_error và _is_missing_object_error 
# được sử dụng để kiểm tra xem một ngoại lệ (exception) 
# có phải là lỗi liên quan đến việc thiếu bucket 
# hoặc object trong hệ thống lưu trữ đối tượng hay không.
def _is_missing_bucket_error(exc: Exception) -> bool:
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False

    error = response.get("Error", {})
    code = str(error.get("Code", ""))
    return code in {"404", "NoSuchBucket", "NotFound"}

# Fuction to check if an exception is a missing object error 
# in S3-compatible storage.
def _is_missing_object_error(exc: Exception) -> bool:
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False

    error = response.get("Error", {})
    code = str(error.get("Code", ""))
    return code in {"404", "NoSuchKey", "NotFound"}
