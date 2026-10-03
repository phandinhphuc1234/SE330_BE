from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from typing import Any, Mapping

from app.core.config import Settings, get_settings


GEMINI_SEARCH_TITLE_TEXT_POLICY = "gemini_search_title_text_v1"


@dataclass(frozen=True)
class BuiltEmbeddingText:
    """Provider-ready text plus stable metadata used for embedding/versioning."""

    text: str
    text_hash: str
    policy: str
    title: str | None = None


class EmbeddingTextBuilder:
    """Build stable embedding input text from clean chunks and metadata.

    The database chunk content should stay clean and citation-friendly. This
    builder creates a separate provider-facing text that adds light semantic
    context such as title, author, chapter and page range. Technical metadata
    like document IDs, object keys, checksums and vector IDs is intentionally
    excluded because it does not help semantic retrieval.
    """

    def __init__(
        self,
        *,
        policy: str | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.policy = policy or self.settings.embedding_text_policy

    def build_document_text(
        self,
        content: str,
        metadata: Mapping[str, Any] | None = None,
    ) -> BuiltEmbeddingText:
        """Build the document/chunk text sent to the embedding provider."""

        normalized_content = _normalize_content(content)
        if not normalized_content:
            raise ValueError("Cannot build embedding text for an empty chunk.")

        metadata = metadata or {}
        title = _resolve_title(metadata)
        body = self._build_context_body(normalized_content, metadata, title)

        if self.policy == GEMINI_SEARCH_TITLE_TEXT_POLICY:
            text = f"title: {title or 'none'} | text: {body}"
        else:
            text = body

        return BuiltEmbeddingText(
            text=text,
            text_hash=_sha256(text),
            policy=self.policy,
            title=title,
        )

    def build_query_text(self, query: str) -> BuiltEmbeddingText:
        """Build the query text sent to the embedding provider."""

        normalized_query = _normalize_single_line(query)
        if not normalized_query:
            raise ValueError("Cannot build embedding text for an empty query.")

        if self.policy == GEMINI_SEARCH_TITLE_TEXT_POLICY:
            text = f"task: search result | query: {normalized_query}"
        else:
            text = normalized_query

        return BuiltEmbeddingText(
            text=text,
            text_hash=_sha256(text),
            policy=self.policy,
        )

    def _build_context_body(
        self,
        content: str,
        metadata: Mapping[str, Any],
        title: str | None,
    ) -> str:
        """Build semantic context lines before the chunk content."""

        context_lines: list[str] = []
        _append_if_present(context_lines, "Book", title)
        _append_if_present(context_lines, "Author", _resolve_author(metadata))
        _append_if_present(context_lines, "Chapter", _resolve_chapter(metadata))
        _append_if_present(context_lines, "Section", _resolve_section(metadata))
        _append_if_present(context_lines, "Page", _resolve_page_range(metadata))

        if not context_lines:
            return f"Content:\n{content}"

        return "\n".join([*context_lines, "", "Content:", content])


def _append_if_present(lines: list[str], label: str, value: str | None) -> None:
    if value:
        lines.append(f"{label}: {value}")


def _resolve_title(metadata: Mapping[str, Any]) -> str | None:
    for key in (
        "book_title",
        "bookTitle",
        "title",
        "document_title",
        "documentTitle",
        "original_filename",
        "originalFilename",
        "filename",
        "file_name",
        "fileName",
    ):
        value = _string_value(metadata.get(key))
        if value:
            return value
    return None


def _resolve_author(metadata: Mapping[str, Any]) -> str | None:
    for key in ("author", "authors", "book_author", "bookAuthor"):
        value = metadata.get(key)
        if isinstance(value, (list, tuple)):
            joined = ", ".join(filter(None, (_string_value(item) for item in value)))
            if joined:
                return joined
        string_value = _string_value(value)
        if string_value:
            return string_value
    return None


def _resolve_chapter(metadata: Mapping[str, Any]) -> str | None:
    for key in ("chapter_title", "chapterTitle", "chapter", "chapter_heading_line"):
        value = _string_value(metadata.get(key))
        if value:
            return value
    return None


def _resolve_section(metadata: Mapping[str, Any]) -> str | None:
    value = metadata.get("section_path")
    if isinstance(value, (list, tuple)):
        section = " > ".join(filter(None, (_string_value(item) for item in value)))
        return section or None
    return _string_value(value)


def _resolve_page_range(metadata: Mapping[str, Any]) -> str | None:
    page_start = _positive_int(metadata.get("pageStart") or metadata.get("page_start"))
    page_end = _positive_int(metadata.get("pageEnd") or metadata.get("page_end"))
    page_number = _positive_int(metadata.get("page_number") or metadata.get("pageNumber"))

    if page_start and page_end:
        if page_start == page_end:
            return str(page_start)
        return f"{page_start}-{page_end}"
    if page_start:
        return str(page_start)
    if page_number:
        return str(page_number)
    return None


def _normalize_content(value: str) -> str:
    """Normalize line endings while preserving paragraph boundaries."""

    value = value.replace("\r\n", "\n").replace("\r", "\n")
    lines = [_normalize_line(line) for line in value.split("\n")]
    normalized = "\n".join(lines)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()


def _normalize_single_line(value: str) -> str:
    return _normalize_line(value.replace("\r", " ").replace("\n", " "))


def _normalize_line(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def _string_value(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, Mapping):
        for key in ("name", "title", "value"):
            string_value = _string_value(value.get(key))
            if string_value:
                return string_value
        return None
    string_value = _normalize_single_line(str(value))
    return string_value or None


def _positive_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and value.isdigit():
        parsed = int(value)
        return parsed if parsed > 0 else None
    return None


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
