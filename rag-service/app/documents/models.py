from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, Integer, JSON, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class Document(Base):
    """RAG document identified by its external source, independent of end users."""

    __tablename__ = "documents"
    __table_args__ = (
        Index("uq_documents_source", "source_type", "source_id", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True, index=True)
    external_document_id: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    source_type: Mapped[str] = mapped_column(String(64), index=True)
    source_id: Mapped[str] = mapped_column(String(255), index=True)
    book_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True, index=True)
    ebook_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True, index=True)
    filename: Mapped[str] = mapped_column(String(512))
    storage_path: Mapped[str] = mapped_column(String(1024))
    metadata_: Mapped[dict] = mapped_column("metadata", JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    chunks = relationship("DocumentChunk", back_populates="document")
    artifacts = relationship("DocumentArtifact", back_populates="document")


class DocumentChunk(Base):
    """Text chunk persisted after parsing and chunking a document."""

    __tablename__ = "document_chunks"

    id: Mapped[int] = mapped_column(primary_key=True, index=True)
    document_id: Mapped[int] = mapped_column(ForeignKey("documents.id"), index=True)
    chunk_index: Mapped[int] = mapped_column(Integer)
    content: Mapped[str] = mapped_column(Text)
    metadata_: Mapped[dict] = mapped_column("metadata", JSON, default=dict)
    vector_id: Mapped[str | None] = mapped_column(String(255), nullable=True)

    document = relationship("Document", back_populates="chunks")


class DocumentArtifact(Base):
    """Object-storage registry for raw files and future ingestion artifacts."""

    __tablename__ = "document_artifacts"
    __table_args__ = (
        # Object keys are only globally unique inside a bucket.
        Index("uq_document_artifacts_bucket_key", "bucket", "object_key", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True, index=True)
    document_id: Mapped[int] = mapped_column(ForeignKey("documents.id"), index=True)
    document_version_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    artifact_type: Mapped[str] = mapped_column(String(64), index=True)
    bucket: Mapped[str] = mapped_column(String(255))
    object_key: Mapped[str] = mapped_column(String(1024))
    content_type: Mapped[str | None] = mapped_column(String(255), nullable=True)
    size_bytes: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    checksum_sha256: Mapped[str | None] = mapped_column(String(128), nullable=True)
    metadata_: Mapped[dict] = mapped_column("metadata", JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    document = relationship("Document", back_populates="artifacts")
