"""Add a multilingual keyword candidate index without rewriting chunk content.

Revision ID: 005_keyword_search_index
Revises: 004_internal_service_schema
"""

from alembic import op

revision = "005_keyword_search_index"
down_revision = "004_internal_service_schema"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE INDEX ix_document_chunks_fts_simple ON document_chunks USING GIN (to_tsvector('simple'::regconfig, content))")
    op.create_index("ix_document_chunks_document_index", "document_chunks", ["document_id", "chunk_index"])


def downgrade() -> None:
    op.drop_index("ix_document_chunks_document_index", table_name="document_chunks")
    op.drop_index("ix_document_chunks_fts_simple", table_name="document_chunks")
