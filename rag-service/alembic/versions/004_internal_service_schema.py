"""convert RAG database to an internal service schema

Revision ID: 004_internal_service_schema
Revises: 003_add_document_artifacts
Create Date: 2026-06-21

This migration intentionally removes RAG-local users, workspaces, chats, and
feedback. Spring Boot is the source of truth for those application concerns.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "004_internal_service_schema"
down_revision: Union[str, None] = "003_add_document_artifacts"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("documents", sa.Column("external_document_id", sa.String(length=255), nullable=True))
    op.add_column("documents", sa.Column("source_type", sa.String(length=64), nullable=True))
    op.add_column("documents", sa.Column("source_id", sa.String(length=255), nullable=True))
    op.add_column("documents", sa.Column("book_id", sa.BigInteger(), nullable=True))
    op.add_column("documents", sa.Column("ebook_id", sa.BigInteger(), nullable=True))

    op.execute(
        sa.text(
            """
            UPDATE documents
            SET external_document_id = 'doc_legacy_' || CAST(id AS VARCHAR),
                source_type = 'STANDALONE_DOCUMENT',
                source_id = 'legacy:' || CAST(id AS VARCHAR)
            """
        )
    )
    op.alter_column("documents", "external_document_id", nullable=False)
    op.alter_column("documents", "source_type", nullable=False)
    op.alter_column("documents", "source_id", nullable=False)

    op.create_index(
        "ix_documents_external_document_id",
        "documents",
        ["external_document_id"],
        unique=True,
    )
    op.create_index("ix_documents_source_type", "documents", ["source_type"])
    op.create_index("ix_documents_source_id", "documents", ["source_id"])
    op.create_index("ix_documents_book_id", "documents", ["book_id"])
    op.create_index("ix_documents_ebook_id", "documents", ["ebook_id"])
    op.create_index(
        "uq_documents_source",
        "documents",
        ["source_type", "source_id"],
        unique=True,
    )

    op.drop_constraint("documents_workspace_id_fkey", "documents", type_="foreignkey")
    op.drop_index("ix_documents_workspace_id", table_name="documents")
    op.drop_column("documents", "workspace_id")

    op.drop_index("ix_feedback_message_id", table_name="feedback")
    op.drop_index("ix_feedback_id", table_name="feedback")
    op.drop_table("feedback")
    op.drop_index("ix_chat_messages_session_id", table_name="chat_messages")
    op.drop_index("ix_chat_messages_id", table_name="chat_messages")
    op.drop_table("chat_messages")
    op.drop_index("ix_chat_sessions_workspace_id", table_name="chat_sessions")
    op.drop_index("ix_chat_sessions_id", table_name="chat_sessions")
    op.drop_table("chat_sessions")
    op.drop_index("ix_workspaces_owner_id", table_name="workspaces")
    op.drop_index("ix_workspaces_id", table_name="workspaces")
    op.drop_table("workspaces")
    op.drop_index("ix_users_email", table_name="users")
    op.drop_index("ix_users_id", table_name="users")
    op.drop_table("users")


def downgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("hashed_password", sa.String(length=255), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_users_id", "users", ["id"])
    op.create_index("ix_users_email", "users", ["email"], unique=True)
    op.execute(
        sa.text(
            """
            INSERT INTO users (id, email, hashed_password, is_active)
            VALUES (1, 'migration-system@local', 'disabled', true)
            """
        )
    )

    op.create_table(
        "workspaces",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("owner_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_workspaces_id", "workspaces", ["id"])
    op.create_index("ix_workspaces_owner_id", "workspaces", ["owner_id"])
    op.execute(sa.text("INSERT INTO workspaces (id, owner_id, name) VALUES (1, 1, 'Migrated')"))

    op.add_column("documents", sa.Column("workspace_id", sa.Integer(), nullable=True))
    op.execute(sa.text("UPDATE documents SET workspace_id = 1"))
    op.alter_column("documents", "workspace_id", nullable=False)
    op.create_foreign_key(
        "documents_workspace_id_fkey",
        "documents",
        "workspaces",
        ["workspace_id"],
        ["id"],
    )
    op.create_index("ix_documents_workspace_id", "documents", ["workspace_id"])

    op.create_table(
        "chat_sessions",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("workspace_id", sa.Integer(), sa.ForeignKey("workspaces.id"), nullable=False),
        sa.Column("title", sa.String(length=255), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_chat_sessions_id", "chat_sessions", ["id"])
    op.create_index("ix_chat_sessions_workspace_id", "chat_sessions", ["workspace_id"])
    op.create_table(
        "chat_messages",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("session_id", sa.Integer(), sa.ForeignKey("chat_sessions.id"), nullable=False),
        sa.Column("role", sa.String(length=32), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("citations", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_chat_messages_id", "chat_messages", ["id"])
    op.create_index("ix_chat_messages_session_id", "chat_messages", ["session_id"])
    op.create_table(
        "feedback",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("message_id", sa.Integer(), sa.ForeignKey("chat_messages.id"), nullable=False),
        sa.Column("rating", sa.Integer(), nullable=False),
        sa.Column("comment", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_feedback_id", "feedback", ["id"])
    op.create_index("ix_feedback_message_id", "feedback", ["message_id"])

    op.drop_index("uq_documents_source", table_name="documents")
    op.drop_index("ix_documents_ebook_id", table_name="documents")
    op.drop_index("ix_documents_book_id", table_name="documents")
    op.drop_index("ix_documents_source_id", table_name="documents")
    op.drop_index("ix_documents_source_type", table_name="documents")
    op.drop_index("ix_documents_external_document_id", table_name="documents")
    op.drop_column("documents", "ebook_id")
    op.drop_column("documents", "book_id")
    op.drop_column("documents", "source_id")
    op.drop_column("documents", "source_type")
    op.drop_column("documents", "external_document_id")
