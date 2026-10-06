"""add document artifacts

Revision ID: 003_add_document_artifacts
Revises: 002_add_ingestion_jobs
Create Date: 2026-06-05
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "003_add_document_artifacts"
down_revision: Union[str, None] = "002_add_ingestion_jobs"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Artifacts store object-storage pointers, not the object bytes themselves.
    op.create_table(
        "document_artifacts",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("document_id", sa.Integer(), sa.ForeignKey("documents.id"), nullable=False),
        sa.Column("document_version_id", sa.Integer(), nullable=True),
        sa.Column("artifact_type", sa.String(length=64), nullable=False),
        sa.Column("bucket", sa.String(length=255), nullable=False),
        sa.Column("object_key", sa.String(length=1024), nullable=False),
        sa.Column("content_type", sa.String(length=255), nullable=True),
        sa.Column("size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("checksum_sha256", sa.String(length=128), nullable=True),
        sa.Column("metadata", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_document_artifacts_id", "document_artifacts", ["id"])
    op.create_index("ix_document_artifacts_document_id", "document_artifacts", ["document_id"])
    op.create_index("ix_document_artifacts_document_version_id", "document_artifacts", ["document_version_id"])
    op.create_index("ix_document_artifacts_artifact_type", "document_artifacts", ["artifact_type"])
    # The same object key may exist in different buckets, so uniqueness uses both fields.
    op.create_index(
        "uq_document_artifacts_bucket_key",
        "document_artifacts",
        ["bucket", "object_key"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("uq_document_artifacts_bucket_key", table_name="document_artifacts")
    op.drop_index("ix_document_artifacts_artifact_type", table_name="document_artifacts")
    op.drop_index("ix_document_artifacts_document_version_id", table_name="document_artifacts")
    op.drop_index("ix_document_artifacts_document_id", table_name="document_artifacts")
    op.drop_index("ix_document_artifacts_id", table_name="document_artifacts")
    op.drop_table("document_artifacts")
