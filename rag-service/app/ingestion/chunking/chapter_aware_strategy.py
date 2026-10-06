"""Opt-in narrative v2: chapter boundaries first, sentence splitting second."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
import hashlib
import json
import re

from llama_index.core import Document
from llama_index.core.schema import BaseNode, MetadataMode
from llama_index.core.utils import get_tokenizer

from app.ingestion.chunking.chapter_boundaries import ChapterBoundary, find_chapter_boundaries
from app.ingestion.chunking.llama_sentence_strategy import NARRATIVE_CHUNKING_STRATEGY
from app.ingestion.cleaners.rules.structure import LineType, classify_pdf_line
from app.ingestion.llama_index.node_adapter import (
    LLAMAINDEX_SENTENCE_CHUNKER, build_sentence_splitter, enrich_sentence_nodes,
)


_SCOPE_KEYS = (
    "document_id", "documentId", "external_document_id", "source_id",
    "bookId", "book_id", "ebookId", "ebook_id", "document_version",
    "document_version_id", "documentVersionId", "source_version",
    "checksum_sha256", "source_checksum_sha256", "sourceType", "source_type",
)
_IDENTITY_KEYS = ("document_id", "documentId", "external_document_id", "source_id", "ebookId", "ebook_id")
_FRONT_MATTER = {"preface", "foreword", "introduction", "intro"}
_UNNUMBERED_SECTIONS = _FRONT_MATTER | {"prologue", "epilogue", "afterword", "conclusion", "appendix"}


@dataclass(frozen=True)
class _SourceSpan:
    document: Document
    page: int
    source_start: int
    section_start: int
    section_end: int


@dataclass
class _Section:
    section_id: str
    chapter: ChapterBoundary | None
    chapter_index: int
    chapter_page: int
    parts: list[str] = field(default_factory=list)
    spans: list[_SourceSpan] = field(default_factory=list)
    length: int = 0
    last_fragment: str = ""

    def append(self, document: Document, page: int, start: int, end: int,
               *, allow_sentence_join: bool = True) -> None:
        text = document.text or ""
        # Only trim outside whitespace, preserving every non-whitespace source
        # character. Mapping always refers to cleaned pages, never PDF bytes.
        fragment = text[start:end]
        start += len(fragment) - len(fragment.lstrip())
        end -= len(fragment) - len(fragment.rstrip())
        if start >= end:
            return
        fragment = text[start:end]
        if self.parts:
            separator = _page_separator(self.last_fragment, fragment) if allow_sentence_join else "\n\n"
            self.parts.append(separator)
            self.length += len(separator)
        self.spans.append(_SourceSpan(document, page, start, self.length, self.length + len(fragment)))
        self.parts.append(fragment)
        self.length += len(fragment)
        self.last_fragment = fragment


class ChapterAwareChunkingStrategy:
    """Keep chunks inside one detected chapter with exact per-page provenance.

    Unknown chapters fall back to independent page sections. Chapter carry and
    joins require consecutive page numbers and identical source identity/version.
    """

    name = NARRATIVE_CHUNKING_STRATEGY
    version = "v2"
    chunker_name = LLAMAINDEX_SENTENCE_CHUNKER

    def build_nodes(
        self, documents: Sequence[Document], *, chunk_size: int, chunk_overlap: int,
    ) -> list[BaseNode]:
        splitter = build_sentence_splitter(
            chunk_size=chunk_size, chunk_overlap=chunk_overlap, include_metadata=False,
        )
        nodes: list[BaseNode] = []
        for section in _build_sections(documents):
            text = "".join(section.parts)
            # Each section is a separate LlamaIndex source Document: token
            # overlap and prev/next relationships cannot cross its boundary.
            section_nodes = splitter.get_nodes_from_documents([
                Document(text=text, id_=section.section_id, metadata={}),
            ])
            ranges = _locate_chunks(text, section_nodes, chunk_overlap=chunk_overlap)
            for node, (start, end) in zip(section_nodes, ranges, strict=True):
                mapped_spans = _map_sources(section, start, end)
                if not mapped_spans:
                    raise ValueError("v2 chunk has no mapped source text.")
                first_span = next(span for span in section.spans if span.section_end > start)
                metadata = {
                    key: value for key, value in dict(first_span.document.metadata or {}).items()
                    if not key.startswith("chapter_") and key != "section_path"
                }
                pages = [span["pageNumber"] for span in mapped_spans]
                metadata.update({
                    "chunking_strategy": self.name,
                    "chunking_strategy_version": self.version,
                    "chunk_level": "child",
                    "chapter_detection_version": "v2",
                    "chapter_detection_source": "rule_based_line_boundary_v2",
                    "chapter_detected": section.chapter is not None,
                    "chapter_detected_on_page": section.chapter is not None and section.chapter_page in pages,
                    "section_id": section.section_id,
                    "section_char_start": start,
                    "section_char_end": end,
                    "source_mapping_version": "cleaned_page_chars_v1",
                    "source_spans": mapped_spans,
                    "page_number": min(pages),
                    "pageStart": min(pages),
                    "pageEnd": max(pages),
                })
                if section.chapter is not None:
                    metadata.update({
                        "chapter_index": section.chapter_index,
                        "chapter_number": section.chapter.number,
                        "chapter_title": section.chapter.title,
                        "chapter_heading_line": section.chapter.title,
                        "chapter_source_page": section.chapter_page,
                        "section_path": [section.chapter.title],
                    })
                node.metadata = metadata
                node.start_char_idx, node.end_char_idx = start, end
                nodes.append(node)
        return enrich_sentence_nodes(
            nodes, chunk_size=splitter.chunk_size, chunk_overlap=splitter.chunk_overlap,
            chunker_name=self.chunker_name,
        )


def _build_sections(documents: Sequence[Document]) -> list[_Section]:
    sections: list[_Section] = []
    active: _Section | None = None
    previous_scope: tuple | None = None
    previous_page: int | None = None
    chapter_index = 0
    fence_state = None
    for document in documents:
        metadata = document.metadata or {}
        page = metadata.get("page_number", metadata.get("pageStart"))
        if type(page) is not int or page <= 0:
            raise ValueError("Narrative v2 requires a positive page_number for source mapping.")
        scope = tuple(metadata.get(key) for key in _SCOPE_KEYS)
        has_identity = any(metadata.get(key) not in (None, "") for key in _IDENTITY_KEYS)
        contiguous = has_identity and scope == previous_scope and page == (previous_page or 0) + 1
        if not contiguous:
            active, fence_state = None, None
        text = document.text or ""
        if not text.strip():
            # Empty/missing pages are a continuity barrier, not evidence that
            # an unfinished sentence or chapter can safely be carried forward.
            active, fence_state = None, None
            previous_scope, previous_page = scope, page
            continue
        page_starts_in_code = fence_state is not None
        boundaries, fence_state = find_chapter_boundaries(text, fence_state=fence_state)
        if (active is not None and active.chapter is not None and boundaries
                and _is_front_matter_title_block(active.chapter, boundaries[0], text[:boundaries[0].offset])):
            # A standalone Markdown title block before main text is not the
            # preceding preface. Keep it source-mapped but unlabelled rather
            # than carrying PREFACE across it or inventing another chapter.
            # Ordinary prose before a mid-page heading still belongs to the
            # old section; this narrow structural guard does not relabel it.
            active = None
        cursor = 0
        for boundary in boundaries:
            if text[cursor:boundary.offset].strip():
                if active is None:
                    active = _new_section(sections, scope, None, chapter_index, page)
                active.append(document, page, cursor, boundary.offset,
                              allow_sentence_join=not page_starts_in_code)
            chapter_index += 1
            active = _new_section(sections, scope, boundary, chapter_index, page)
            cursor = boundary.offset
        if text[cursor:].strip():
            if active is None:
                active = _new_section(sections, scope, None, chapter_index, page)
            active.append(document, page, cursor, len(text), allow_sentence_join=not page_starts_in_code)
        if active is not None and active.chapter is None:
            active = None  # No reliable chapter: keep page-local fallback.
        previous_scope, previous_page = scope, page
    return [section for section in sections if section.spans]


def _is_front_matter_title_block(previous: ChapterBoundary, upcoming: ChapterBoundary, prefix: str) -> bool:
    if (previous.number.casefold() not in _FRONT_MATTER
            or upcoming.number.casefold() in _UNNUMBERED_SECTIONS):
        return False
    lines = [line for line in prefix.splitlines() if line.strip()]
    return bool(lines) and all(re.fullmatch(r" {0,3}#{1,6}[ \t]+\S.*", line) for line in lines)


def _new_section(sections: list[_Section], scope: tuple, chapter: ChapterBoundary | None,
                 index: int, page: int) -> _Section:
    digest = hashlib.sha256(json.dumps(scope, ensure_ascii=False).encode("utf-8")).hexdigest()[:12]
    section = _Section(f"section-{digest}-{len(sections)}", chapter, index, page)
    sections.append(section)
    return section


def _page_separator(left: str, right: str) -> str:
    """Join likely sentence continuations; otherwise retain a paragraph break."""

    last_line, first_line = left.splitlines()[-1], right.splitlines()[0]
    if (find_chapter_boundaries(left)[1] is None
            and classify_pdf_line(last_line) == LineType.PARAGRAPH
            and classify_pdf_line(first_line) == LineType.PARAGRAPH
            and not re.search(r'[.!?…:;\-–—][\"\'”’)]*$', left)
            and right[0].islower()):
        return " "
    return "\n\n"


def _locate_chunks(text: str, nodes: Sequence[BaseNode], *, chunk_overlap: int) -> list[tuple[int, int]]:
    """Locate exact substrings with monotonic coverage; fail on unmappable text.

    Do not trust first-occurrence find() or stale LlamaIndex offsets for repeated
    passages. Bound a candidate's overlap with the same default tokenizer used
    by SentenceSplitter; repeated text must not create arbitrarily huge overlap.
    Zero overlap forbids searching inside the preceding chunk.
    """

    contents = [node.get_content(metadata_mode=MetadataMode.NONE) for node in nodes]
    if not contents:
        if text.strip():
            raise ValueError("Narrative v2 splitter left unmapped source content.")
        return []
    tokenizer = get_tokenizer()
    source_end = len(text.rstrip())

    def candidates(index: int, previous_start: int, previous_end: int):
        content = contents[index]
        if not content:
            return
        search_start = (previous_start + 1 if chunk_overlap else previous_end) if index else 0
        # Anchor the final chunk at the end of the source. A locally plausible
        # repeated occurrence is insufficient if it leaves the tail unmapped.
        final = index == len(contents) - 1
        start = source_end - len(content) if final else text.find(content, search_start)
        while start >= search_start:
            end = start + len(content)
            if start > previous_end and text[previous_end:start].strip():
                return  # Later occurrences would skip the same source text.
            if (end > previous_end and text[start:end] == content
                    and (start >= previous_end
                         or len(tokenizer(text[start:previous_end].strip())) <= chunk_overlap)):
                yield start, end
            if final:
                return
            start = text.find(content, start + 1)

    # Iterative, memoized search avoids recursion-depth failures on long books.
    # Usually there is one candidate; backtrack only for genuinely repetitive
    # passages where greedy matching would misattribute or omit source text.
    initial = (0, -1, 0)
    stack = [(initial, candidates(*initial))]
    ranges: list[tuple[int, int]] = []
    failed: set[tuple[int, int, int]] = set()
    while stack:
        state, options = stack[-1]
        candidate = next(options, None)
        if candidate is None:
            failed.add(state)
            stack.pop()
            if ranges:
                ranges.pop()
            continue
        if state[0] == len(contents) - 1:
            return [*ranges, candidate]
        following = (state[0] + 1, *candidate)
        if following not in failed:
            ranges.append(candidate)
            stack.append((following, candidates(*following)))
    raise ValueError("Narrative v2 cannot map splitter text without skipping source content.")


def _map_sources(section: _Section, start: int, end: int) -> list[dict]:
    mapped: list[dict] = []
    for span in section.spans:
        overlap_start, overlap_end = max(start, span.section_start), min(end, span.section_end)
        if overlap_start >= overlap_end:
            continue
        mapped.append({
            "pageNumber": span.page,
            "sourceCharStart": span.source_start + overlap_start - span.section_start,
            "sourceCharEnd": span.source_start + overlap_end - span.section_start,
            "chunkCharStart": overlap_start - start,
            "chunkCharEnd": overlap_end - start,
        })
    return mapped
