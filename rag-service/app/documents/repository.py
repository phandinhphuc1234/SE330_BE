from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import delete, select

from app.documents.models import Document, DocumentArtifact, DocumentChunk

# Đây là module repository cho tài liệu, 
# chứa các hàm để tương tác với cơ sở dữ liệu liên quan đến tài liệu,
async def create_document(
    session: AsyncSession,
    external_document_id: str,
    source_type: str,
    source_id: str,
    filename: str,
    storage_path: str,
    book_id: int | None = None,
    ebook_id: int | None = None,
    metadata: dict | None = None,
) -> Document:
    document = Document(
        external_document_id=external_document_id,
        source_type=source_type,
        source_id=source_id,
        book_id=book_id,
        ebook_id=ebook_id,
        filename=filename,
        storage_path=storage_path,
        metadata_=metadata or {},
    )
    session.add(document)
    await session.flush()
    return document


async def get_document_by_source(
    session: AsyncSession,
    source_type: str,
    source_id: str,
) -> Document | None:
    result = await session.execute(
        select(Document)
        .where(Document.source_type == source_type)
        .where(Document.source_id == source_id)
    )
    return result.scalar_one_or_none()

# Hàm này truy vấn cơ sở dữ liệu để lấy một tài liệu dựa trên document_id.
async def get_document(session: AsyncSession, document_id: int) -> Document | None:
    result = await session.execute(select(Document).where(Document.id == document_id))
    return result.scalar_one_or_none()

# Hàm này cập nhật metadata của một tài liệu đã tồn tại trong cơ sở dữ liệu.
async def update_document_metadata(
    session: AsyncSession,
    document: Document,
    metadata: dict,
) -> Document:
    document.metadata_ = metadata
    session.add(document)
    await session.flush()
    return document

# Hàm này tạo một artifact mới liên kết với một tài liệu cụ thể,
async def create_document_artifact(
    session: AsyncSession,
    document_id: int,
    artifact_type: str,
    bucket: str,
    object_key: str,
    content_type: str | None = None,
    size_bytes: int | None = None,
    checksum_sha256: str | None = None,
    metadata: dict | None = None,
    document_version_id: int | None = None,
) -> DocumentArtifact:
    artifact = DocumentArtifact(
        document_id=document_id,
        document_version_id=document_version_id,
        artifact_type=artifact_type,
        bucket=bucket,
        object_key=object_key,
        content_type=content_type,
        size_bytes=size_bytes,
        checksum_sha256=checksum_sha256,
        metadata_=metadata or {},
    )
    session.add(artifact)
    await session.flush()
    return artifact

# Hàm này truy vấn cơ sở dữ liệu để lấy một artifact dựa trên document_id và artifact_type.
async def get_document_artifact(
    session: AsyncSession,
    document_id: int,
    artifact_type: str,
) -> DocumentArtifact | None:
    result = await session.execute(
        select(DocumentArtifact)
        .where(DocumentArtifact.document_id == document_id)
        .where(DocumentArtifact.artifact_type == artifact_type)
        .order_by(DocumentArtifact.id.desc())
    )
    return result.scalar_one_or_none()


async def update_document_artifact(
    session: AsyncSession,
    artifact: DocumentArtifact,
    *,
    bucket: str,
    object_key: str,
    content_type: str | None,
    size_bytes: int | None,
    checksum_sha256: str | None,
    metadata: dict | None = None,
) -> DocumentArtifact:
    artifact.bucket = bucket
    artifact.object_key = object_key
    artifact.content_type = content_type
    artifact.size_bytes = size_bytes
    artifact.checksum_sha256 = checksum_sha256
    artifact.metadata_ = metadata or {}
    session.add(artifact)
    await session.flush()
    return artifact

# Hàm này xóa tất cả các chunk liên quan đến một tài liệu cụ thể,
async def delete_chunks_for_document(session: AsyncSession, document_id: int) -> None:
    await session.execute(delete(DocumentChunk).where(DocumentChunk.document_id == document_id))

# Hàm này tạo một chunk mới liên kết với một tài liệu cụ thể,
async def create_document_chunk(
    session: AsyncSession,
    document_id: int,
    chunk_index: int,
    content: str,
    metadata: dict | None = None,
    vector_id: str | None = None,
) -> DocumentChunk:
    chunk = DocumentChunk(
        document_id=document_id,
        chunk_index=chunk_index,
        content=content,
        metadata_=metadata or {},
        vector_id=vector_id,
    )
    session.add(chunk)
    await session.flush()
    return chunk


async def update_document_chunk_metadata(
    session: AsyncSession,
    chunk: DocumentChunk,
    *,
    metadata: dict,
    vector_id: str | None = None,
) -> DocumentChunk:
    """Update chunk metadata after later ingestion stages mutate it.

    The parser/cleaner/chunker creates the initial chunk metadata. Embedding and
    Qdrant indexing happen after chunks are persisted, so this helper keeps the
    database row in sync with the in-memory chunk after vector indexing succeeds.
    """

    chunk.metadata_ = metadata
    chunk.vector_id = vector_id
    session.add(chunk)
    await session.flush()
    return chunk
