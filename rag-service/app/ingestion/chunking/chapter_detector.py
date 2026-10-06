import re
from dataclasses import dataclass
from typing import Any

from llama_index.core import Document

CHAPTER_DETECTION_VERSION = "v1"
CHAPTER_DETECTION_SOURCE = "rule_based_page_heading_v1"

_NUMBER_TOKEN_PATTERN = (
    r"[0-9]+"
    r"|[ivxlcdm]+"
    r"|one|two|three|four|five|six|seven|eight|nine|ten"
    r"|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty"
    r"|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth"
)

_NUMBERED_CHAPTER_HEADING_PATTERN = re.compile(
    r"""
    ^\s*
    (?P<label>
        chương|chuong|phần|phan|hồi|hoi|quyển|quyen|tập|tap
        |chapter|chap\.?|part|book|volume|vol\.?|act
    )
    \s+
    (?P<number>""" + _NUMBER_TOKEN_PATTERN + r""")
    (?P<suffix>\s*(?:[:.\-–—]\s*)?.*)?
    \s*$
    """,
    re.IGNORECASE | re.VERBOSE,
)

_UNNUMBERED_CHAPTER_HEADING_PATTERN = re.compile(
    r"""
    ^\s*
    (?P<label>
        prologue|epilogue|preface|foreword|afterword|introduction|intro|conclusion
    )
    (?P<suffix>\s*(?:[:.\-–—]\s*)?.*)?
    \s*$
    """,
    re.IGNORECASE | re.VERBOSE,
)


@dataclass(frozen=True)
class ChapterMatch:
    """One chapter heading found near the top of a cleaned page."""

    chapter_index: int
    chapter_title: str
    chapter_number: str
    source_page: int | None
    matched_line: str
    matched_line_index: int

    def to_metadata(self) -> dict[str, Any]:
        """Return chunk/document metadata for the active chapter."""

        return {
            "chapter_detected": True,
            "chapter_index": self.chapter_index,
            "chapter_number": self.chapter_number,
            "chapter_title": self.chapter_title,
            "chapter_source_page": self.source_page,
            "chapter_heading_line": self.matched_line,
            "section_path": [self.chapter_title],
        }


class ChapterDetector:
    """Attach lightweight chapter metadata to page-level LlamaIndex Documents.

    C6 deliberately stays conservative. It only looks for chapter-like headings
    in the first few non-empty lines of each cleaned page, then carries the
    active chapter forward to later pages. It does not split by chapter yet;
    LlamaIndex still performs the actual node/chunk splitting.
    """

    def __init__(
        self,
        *,
        max_heading_scan_lines: int = 8,
        max_heading_chars: int = 120,
    ) -> None:
        self.max_heading_scan_lines = max_heading_scan_lines
        self.max_heading_chars = max_heading_chars

    def attach_metadata(self, documents: list[Document]) -> list[Document]:
        """Return copied documents with chapter metadata attached."""

        chapter_sequence = 0
        active_chapter: ChapterMatch | None = None
        output_documents: list[Document] = []

        for document in documents:
            page_number = _resolve_page_number(document.metadata or {})
            detected_heading = self._detect_heading(document.text or "")
            detected_on_page = detected_heading is not None
            if detected_heading is not None:
                chapter_sequence += 1
                active_chapter = ChapterMatch(
                    chapter_index=chapter_sequence,
                    chapter_title=detected_heading["title"],
                    chapter_number=detected_heading["number"],
                    source_page=page_number,
                    matched_line=detected_heading["line"],
                    matched_line_index=detected_heading["line_index"],
                )

            metadata = {
                **dict(document.metadata or {}),
                "chapter_detection_version": CHAPTER_DETECTION_VERSION,
                "chapter_detection_source": CHAPTER_DETECTION_SOURCE,
                "chapter_detected_on_page": detected_on_page,
            }
            if active_chapter is not None:
                metadata.update(active_chapter.to_metadata())
            else:
                metadata["chapter_detected"] = False

            output_documents.append(
                Document(
                    text=document.text or "",
                    metadata=metadata,
                    id_=document.id_,
                )
            )

        return output_documents

    def _detect_heading(self, text: str) -> dict[str, Any] | None:
        non_empty_lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
        for line_index, line in enumerate(non_empty_lines[: self.max_heading_scan_lines]):
            normalized_line = _normalize_heading_line(line)
            if not normalized_line or len(normalized_line) > self.max_heading_chars:
                continue
            if normalized_line.startswith("|"):
                continue

            numbered_match = _NUMBERED_CHAPTER_HEADING_PATTERN.match(normalized_line)
            if numbered_match is not None:
                return {
                    "line": normalized_line,
                    "line_index": line_index,
                    "number": numbered_match.group("number"),
                    "title": normalized_line,
                }

            unnumbered_match = _UNNUMBERED_CHAPTER_HEADING_PATTERN.match(normalized_line)
            if unnumbered_match is not None:
                return {
                    "line": normalized_line,
                    "line_index": line_index,
                    "number": unnumbered_match.group("label").casefold().replace(".", ""),
                    "title": normalized_line,
                }
        return None


def _normalize_heading_line(line: str) -> str:
    return " ".join((line or "").strip().split())


def _resolve_page_number(metadata: dict[str, Any]) -> int | None:
    page_number = metadata.get("page_number")
    if isinstance(page_number, int) and page_number > 0:
        return page_number
    page_start = metadata.get("pageStart")
    if isinstance(page_start, int) and page_start > 0:
        return page_start
    return None
