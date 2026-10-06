"""Document-local graph edges with immutable source-chunk provenance."""

from sqlalchemy import ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class DocumentGraphEdge(Base):
    __tablename__ = "document_graph_edges"
    __table_args__ = (
        Index("ix_graph_edges_document_source", "document_id", "source_key"),
        Index("ix_graph_edges_document_target", "document_id", "target_key"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    document_id: Mapped[int] = mapped_column(ForeignKey("documents.id", ondelete="CASCADE"))
    chunk_id: Mapped[int] = mapped_column(ForeignKey("document_chunks.id", ondelete="CASCADE"), index=True)
    source: Mapped[str] = mapped_column(String(255))
    source_key: Mapped[str] = mapped_column(String(255))
    target: Mapped[str] = mapped_column(String(255))
    target_key: Mapped[str] = mapped_column(String(255))
    relation: Mapped[str] = mapped_column(String(128))
    evidence_quote: Mapped[str] = mapped_column(Text)
    embedding_version: Mapped[str] = mapped_column(String(255))
    extraction_version: Mapped[str] = mapped_column(String(64))

