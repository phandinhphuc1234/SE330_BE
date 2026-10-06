"""Line/block structure detection for cleaned PDF text.

The cleaner needs a lightweight understanding of text structure before it can
apply stronger rules such as line merging or hyphenation repair. This module is
intentionally heuristic: it gives us safe categories for protection and later
metadata, not a full document layout parser.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from enum import StrEnum
import re


class LineType(StrEnum):
    """Small set of line categories the cleaner can reason about safely."""

    BLANK = "blank"
    HEADING = "heading"
    LIST = "list"
    TABLE = "table"
    CODE = "code"
    CAPTION = "caption"
    PARAGRAPH = "paragraph"


@dataclass(frozen=True)
class LineClassification:
    """Classification result for one line of parsed/cleaned page text."""

    line_number: int
    text: str
    line_type: LineType


FENCED_CODE_RE = re.compile(r"^\s*(```|~~~)")
MARKDOWN_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+\S")
NUMBERED_HEADING_RE = re.compile(r"^\s{0,3}\d+(?:\.\d+){1,5}\s+\S")
CHAPTER_HEADING_RE = re.compile(r"^\s{0,3}(chapter|section|part|chương)\s+[\wIVXLCDMivxlcdm\d]+(?:\s*[:.-]\s*|\s+)\S*", re.IGNORECASE)
LIST_RE = re.compile(r"^\s{0,6}(?:[-*+•‣◦]\s+|\d{1,3}[.)]\s+|[A-Za-z][.)]\s+)")
CAPTION_RE = re.compile(
    r"^\s{0,3}(?:fig(?:ure)?|table|hình|hinh|bảng|bang)\s+\d+(?:\.\d+)*\s*[:.-]\s+\S",
    re.IGNORECASE,
)
MARKDOWN_TABLE_SEPARATOR_RE = re.compile(r"^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$")
STRUCTURAL_LINE_TYPES = {LineType.HEADING, LineType.LIST, LineType.TABLE, LineType.CODE, LineType.CAPTION}


def classify_pdf_lines(text: str) -> list[LineClassification]:
    """Classify every line while tracking fenced-code state."""

    classifications: list[LineClassification] = []
    in_fenced_code = False

    for index, line in enumerate(text.split("\n"), start=1):
        line_type = classify_pdf_line(line, in_fenced_code=in_fenced_code)
        classifications.append(LineClassification(line_number=index, text=line, line_type=line_type))

        if FENCED_CODE_RE.match(line):
            in_fenced_code = not in_fenced_code

    return classifications


def count_pdf_line_types(text: str) -> dict[str, int]:
    """Return stable line-type counts for page/chunk metadata.

    The returned dict always contains every known ``LineType`` key. Stable keys
    make downstream chunking/artifact code easier to consume than a sparse
    counter whose shape changes from page to page.
    """

    if not text:
        return {line_type.value: 0 for line_type in LineType}

    counts = Counter(classification.line_type for classification in classify_pdf_lines(text))
    return {line_type.value: counts.get(line_type, 0) for line_type in LineType}


def has_structural_lines(line_type_counts: dict[str, int]) -> bool:
    """Return whether a page contains structure worth preserving for chunking."""

    return any(line_type_counts.get(line_type.value, 0) > 0 for line_type in STRUCTURAL_LINE_TYPES)


def classify_pdf_line(line: str, *, in_fenced_code: bool = False) -> LineType:
    """Classify one line using conservative Markdown/PDF extraction patterns."""

    stripped = line.strip()
    if not stripped:
        return LineType.BLANK

    if in_fenced_code or FENCED_CODE_RE.match(line):
        return LineType.CODE

    if _is_markdown_table_line(stripped):
        return LineType.TABLE

    if CAPTION_RE.match(line):
        return LineType.CAPTION

    if LIST_RE.match(line):
        return LineType.LIST

    if _is_heading_line(line):
        return LineType.HEADING

    if _looks_like_indented_code(line):
        return LineType.CODE

    return LineType.PARAGRAPH


def _is_markdown_table_line(stripped_line: str) -> bool:
    """Return whether a line looks like a Markdown table row or separator."""

    if MARKDOWN_TABLE_SEPARATOR_RE.match(stripped_line):
        return True
    return stripped_line.startswith("|") and stripped_line.endswith("|") and stripped_line.count("|") >= 2


def _is_heading_line(line: str) -> bool:
    """Detect headings without confusing page numbers for section titles."""

    stripped = line.strip()
    if MARKDOWN_HEADING_RE.match(line):
        return True
    if CHAPTER_HEADING_RE.match(line):
        return True

    # Require at least one dotted level, e.g. "3.2 Borrowing Module". A plain
    # "12" is probably a page number, not a heading.
    return bool(NUMBERED_HEADING_RE.match(stripped))


def _looks_like_indented_code(line: str) -> bool:
    """Detect simple indented code/config lines while avoiding nested lists."""

    leading_spaces = len(line) - len(line.lstrip(" "))
    if leading_spaces < 4:
        return False
    return not LIST_RE.match(line)
