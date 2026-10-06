"""Fail-closed identity/revision and chapter rules for neighbour expansion.

These checks restrict optional expansion; they do not replace the original
Library authorization filters or remove independently retrieved seed evidence.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable


def non_negative_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        return None
    if isinstance(value, str) and not value.strip().isdigit():
        return None
    try:
        number = int(value)
    except ValueError:
        return None
    return number if number >= 0 else None


def positive_int(value: Any) -> int | None:
    number = non_negative_int(value)
    return number if number is not None and number > 0 else None


def text_value(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def read_alias(metadata: dict, keys: tuple[str, ...], parser: Callable = text_value):
    """Resolve snake/camel aliases, refusing malformed or conflicting values."""

    values = [parser(metadata[key]) for key in keys if metadata.get(key) is not None]
    if any(value is None for value in values) or (values and any(value != values[0] for value in values)):
        raise ValueError(f"Invalid or conflicting context metadata aliases: {keys}")
    return values[0] if values else None


# If either side has a revision/policy field, the other must supply the SAME
# value. Missing metadata must never silently widen a versioned seed's scope.
REVISION_FIELDS = (
    (("chunking_strategy_version", "chunkingStrategyVersion"), text_value),
    (("chunking_strategy",), text_value),
    (("cleaning_version",), text_value),
    (("chunker",), text_value),
    (("chunk_size",), positive_int),
    (("chunk_overlap",), non_negative_int),
    (("document_version_id", "documentVersionId"), positive_int),
    (("document_version", "documentVersion"), text_value),
    (("source_version", "sourceVersion"), text_value),
    (("source_checksum_sha256",), text_value),
    (("checksum_sha256",), text_value),
    (("artifact_version_key",), text_value),
    (("artifact_version",), text_value),
    (("sourceType",), text_value),
)


@dataclass(frozen=True)
class ExpansionScope:
    document_id: int
    ebook_id: int
    book_id: int | None
    external_document_id: str | None
    embedding_version: str
    revisions: tuple


def expansion_scope(metadata: dict, *, document_id: int | None = None) -> ExpansionScope | None:
    """No ebook/version identity, contradictory aliases or inactive => no expansion."""

    try:
        if metadata.get("active", True) is not True:
            return None
        internal = read_alias(metadata, ("document_id", "documentInternalId"), positive_int)
        if internal is None:
            internal = positive_int(document_id)
        elif document_id is not None and internal != document_id:
            return None
        ebook = read_alias(metadata, ("ebook_id", "ebookId"), positive_int)
        embedding = read_alias(metadata, ("embedding_version", "embeddingVersion"))
        if internal is None or ebook is None or embedding is None:
            return None
        revisions = tuple(read_alias(metadata, keys, parser) for keys, parser in REVISION_FIELDS)
        if revisions[0] == "v2":
            # New v2 ingestion carries the full PDF checksum. A raw v2 node or
            # old unversioned payload is not proof of a retrievable source revision.
            revision_keys = ("source_checksum_sha256", "document_version_id", "document_version",
                             "source_version", "artifact_version_key", "artifact_version")
            known_revisions = {keys[0]: value for (keys, _), value in zip(REVISION_FIELDS, revisions, strict=True)}
            if not any(known_revisions.get(key) is not None for key in revision_keys):
                return None
            if read_alias(metadata, ("section_id",)) is None:
                return None
        return ExpansionScope(
            internal, ebook,
            read_alias(metadata, ("book_id", "bookId"), positive_int),
            read_alias(metadata, ("external_document_id", "documentId")),
            embedding, revisions,
        )
    except ValueError:
        return None


def chunk_index(metadata: dict, *, fallback: int | None = None) -> int | None:
    try:
        index = read_alias(metadata, ("chunk_index", "chunkIndex"), non_negative_int)
        if index is not None and fallback is not None and index != fallback:
            return None
        return index if index is not None else non_negative_int(fallback)
    except ValueError:
        return None


def page_interval(metadata: dict) -> tuple[int, int] | None:
    try:
        start = read_alias(metadata, ("pageStart", "page_start"), positive_int)
        number = read_alias(metadata, ("page_number",), positive_int)
        if start is None:
            start = number
        elif number is not None and start != number:
            return None
        end = read_alias(metadata, ("pageEnd", "page_end"), positive_int)
        end = start if end is None else end
        return (start, end) if start is not None and end is not None and end >= start else None
    except ValueError:
        return None


def chapter_relation(seed: dict, neighbor: dict) -> str | None:
    """Prefer v2 section, then v1 chapter; unknown chapters stay page-local."""

    try:
        seed_page, neighbor_page = page_interval(seed), page_interval(neighbor)
        if seed_page is None or neighbor_page is None:
            return None
        section = read_alias(seed, ("section_id",))
        other_section = read_alias(neighbor, ("section_id",))
        chapter, other_chapter = _chapter_identity(seed), _chapter_identity(neighbor)
        if section is not None or other_section is not None:
            if section is None or section != other_section or chapter != other_chapter:
                return None
            if chapter is None:
                return "same_page_fallback" if seed_page == neighbor_page and seed_page[0] == seed_page[1] else None
            return "same_section"
        if chapter is not None or other_chapter is not None:
            if chapter is None or chapter != other_chapter:
                return None
            source_page = read_alias(seed, ("chapter_source_page",), positive_int)
            other_source_page = read_alias(neighbor, ("chapter_source_page",), positive_int)
            if source_page is not None and other_source_page is not None and source_page != other_source_page:
                return None
            return "same_chapter"
        if seed_page == neighbor_page and seed_page[0] == seed_page[1]:
            return "same_page_fallback"
        return None
    except ValueError:
        return None


def _chapter_identity(metadata: dict) -> tuple | None:
    detected = metadata.get("chapter_detected")
    if detected is not None and not isinstance(detected, bool):
        raise ValueError("chapter_detected must be a boolean when supplied.")
    if metadata.get("chapter_detected") is False:
        return None
    index = read_alias(metadata, ("chapter_index", "chapterIndex"), positive_int)
    title = read_alias(metadata, ("chapter_title", "chapterTitle"))
    if index is None or title is None:
        return None
    return index, " ".join(title.casefold().split())
