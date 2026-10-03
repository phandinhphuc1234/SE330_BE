"""Remove simple page-number lines from cleaned PDF pages.

This rule is intentionally conservative. It only removes lines that look like
standalone page numbers and only when those lines appear near the top or bottom
edge of a page. Section headings such as ``3.2 Borrowing Module`` must survive.
"""

from __future__ import annotations

from dataclasses import dataclass
import re


PAGE_NUMBER_PATTERNS = [
    re.compile(r"^\s*\d{1,5}\s*$"),
    re.compile(r"^\s*[-–—]\s*\d{1,5}\s*[-–—]\s*$"),
    re.compile(r"^\s*\d{1,5}\s*/\s*\d{1,5}\s*$"),
    re.compile(r"^\s*page\s+\d{1,5}(?:\s+(?:of|/)\s+\d{1,5})?\s*$", re.IGNORECASE),
    re.compile(r"^\s*p\.\s*\d{1,5}(?:\s+(?:of|/)\s+\d{1,5})?\s*$", re.IGNORECASE),
    re.compile(r"^\s*trang\s+\d{1,5}(?:\s*/\s*\d{1,5})?\s*$", re.IGNORECASE),
]


@dataclass(frozen=True)
class PageNumberRemovalResult:
    """Result of removing page-number noise from one page."""

    text: str
    removed_count: int
    removed_lines: list[str]


def remove_page_numbers(text: str, *, scan_lines: int = 2) -> PageNumberRemovalResult:
    """Remove standalone page-number lines from page edges.

    ``scan_lines`` controls how many non-blank lines are considered at each edge.
    When a page has very few non-blank lines, the rule only checks the first and
    last non-blank line. That avoids deleting body text that merely appears in a
    short page sample.
    """

    if not text:
        return PageNumberRemovalResult(text=text, removed_count=0, removed_lines=[])

    lines = text.split("\n")
    candidate_indexes = _candidate_edge_indexes(lines, scan_lines=max(1, scan_lines))
    removed_lines: list[str] = []
    kept_lines: list[str] = []

    for index, line in enumerate(lines):
        if index in candidate_indexes and _is_page_number_line(line):
            removed_lines.append(line.strip())
            continue
        kept_lines.append(line)

    return PageNumberRemovalResult(
        text="\n".join(kept_lines),
        removed_count=len(removed_lines),
        removed_lines=removed_lines,
    )


def _candidate_edge_indexes(lines: list[str], *, scan_lines: int) -> set[int]:
    """Return non-blank line indexes near page edges."""

    nonblank_indexes = [index for index, line in enumerate(lines) if line.strip()]
    if not nonblank_indexes:
        return set()

    # On very short pages, top/bottom windows would overlap and accidentally
    # turn the whole page into "edge". Only checking the outermost non-blank
    # lines is safer for notes, title pages, and test snippets.
    if len(nonblank_indexes) <= scan_lines * 2:
        return {nonblank_indexes[0], nonblank_indexes[-1]}

    return set(nonblank_indexes[:scan_lines]) | set(nonblank_indexes[-scan_lines:])


def _is_page_number_line(line: str) -> bool:
    """Return true when a line is only a page-number marker."""

    stripped = line.strip()
    if not stripped:
        return False
    return any(pattern.match(stripped) for pattern in PAGE_NUMBER_PATTERNS)
