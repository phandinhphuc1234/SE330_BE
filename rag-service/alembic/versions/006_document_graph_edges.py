"""Add optional document-local graph evidence.

Revision ID: 006_document_graph_edges
Revises: 005_keyword_search_index
"""

from alembic import op
import sqlalchemy as sa

revision = "006_document_graph_edges"
down_revision = "005_keyword_search_index"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table("document_graph_edges",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("document_id", sa.Integer(), sa.ForeignKey("documents.id", ondelete="CASCADE"), nullable=False),
        sa.Column("chunk_id", sa.Integer(), sa.ForeignKey("document_chunks.id", ondelete="CASCADE"), nullable=False),
        sa.Column("source", sa.String(255), nullable=False), sa.Column("source_key", sa.String(255), nullable=False),
        sa.Column("target", sa.String(255), nullable=False), sa.Column("target_key", sa.String(255), nullable=False),
        sa.Column("relation", sa.String(128), nullable=False), sa.Column("evidence_quote", sa.Text(), nullable=False),
        sa.Column("embedding_version", sa.String(255), nullable=False),
        sa.Column("extraction_version", sa.String(64), nullable=False),
    )
    op.create_index("ix_graph_edges_document_source", "document_graph_edges", ["document_id", "source_key"])
    op.create_index("ix_graph_edges_document_target", "document_graph_edges", ["document_id", "target_key"])
    op.create_index("ix_document_graph_edges_chunk_id", "document_graph_edges", ["chunk_id"])


def downgrade() -> None:
    op.drop_table("document_graph_edges")
