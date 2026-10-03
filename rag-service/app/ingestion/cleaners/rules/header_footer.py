"""Detect repeated header/footer candidates across cleaned PDF pages.

This is Step 9A: detect/report only. The rule does not remove text. Repeated
header/footer removal is intentionally a separate step because false positives
can delete real chapter titles or important context.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
import math
import re


MAX_CANDIDATE_CHARS = 140


@dataclass(frozen=True)
class HeaderFooterCandidate:
    """One repeated line candidate found near a page edge."""

    line: str
    normalized_line: str
    position: str
    occurrence_count: int


@dataclass(frozen=True)
class HeaderFooterDetectionResult:
    """Document-level repeated header/footer detection output."""

    page_candidates: list[list[HeaderFooterCandidate]]
    repeated_normalized_lines: list[str]
    min_repeat_count: int
    total_pages: int

    @classmethod
    def empty(cls, total_pages: int, *, min_repeat_count: int = 0) -> "HeaderFooterDetectionResult":
        """Build an empty detection result with one candidate list per page."""

        return cls(
            page_candidates=[[] for _ in range(total_pages)],
            repeated_normalized_lines=[],
            min_repeat_count=min_repeat_count,
            total_pages=total_pages,
        )


@dataclass(frozen=True)
class HeaderFooterRemovalResult:
    """Result of removing repeated header/footer candidates from one page."""

    text: str
    removed_count: int
    removed_lines: list[str]


def detect_repeated_header_footer(
    page_texts: Sequence[str],
    *,
    top_lines: int = 3,
    bottom_lines: int = 3,
    min_repeat_ratio: float = 0.4,
    min_page_count: int = 3,
) -> HeaderFooterDetectionResult:
    """Detect repeated edge lines across pages without removing them."""

    total_pages = len(page_texts)
    min_repeat_count = _minimum_repeat_count(total_pages, min_repeat_ratio)
    if total_pages < min_page_count:
        return HeaderFooterDetectionResult.empty(total_pages, min_repeat_count=min_repeat_count)

    edge_candidates_by_page = [
        _collect_edge_candidates(page_text, top_lines=max(0, top_lines), bottom_lines=max(0, bottom_lines))
        for page_text in page_texts
    ]

    occurrence_counter: Counter[str] = Counter()
    for page_candidates in edge_candidates_by_page:
        occurrence_counter.update({candidate.normalized_line for candidate in page_candidates})

    repeated_normalized_lines = sorted(
        normalized_line
        for normalized_line, count in occurrence_counter.items()
        if count >= min_repeat_count
    )
    repeated_set = set(repeated_normalized_lines)

    page_candidates: list[list[HeaderFooterCandidate]] = []
    for edge_candidates in edge_candidates_by_page:
        page_candidates.append(
            [
                HeaderFooterCandidate(
                    line=candidate.line,
                    normalized_line=candidate.normalized_line,
                    position=candidate.position,
                    occurrence_count=occurrence_counter[candidate.normalized_line],
                )
                for candidate in edge_candidates
                if candidate.normalized_line in repeated_set
            ]
        )

    return HeaderFooterDetectionResult(
        page_candidates=page_candidates,
        repeated_normalized_lines=repeated_normalized_lines,
        min_repeat_count=min_repeat_count,
        total_pages=total_pages,
    )


def remove_repeated_header_footer(
    page_text: str,
    candidates: Sequence[HeaderFooterCandidate],
    *,
    top_lines: int = 3,
    bottom_lines: int = 3,
) -> HeaderFooterRemovalResult:
    """Remove repeated header/footer candidate lines from page edges only."""

    if not page_text or not candidates:
        return HeaderFooterRemovalResult(text=page_text, removed_count=0, removed_lines=[])

    repeated_normalized_lines = {candidate.normalized_line for candidate in candidates}
    candidate_indexes = _candidate_edge_indexes(
        page_text.split("\n"),
        top_lines=max(0, top_lines),
        bottom_lines=max(0, bottom_lines),
    )
    kept_lines: list[str] = []
    removed_lines: list[str] = []

    for index, line in enumerate(page_text.split("\n")):
        normalized_line = _normalize_candidate_line(line)
        if index in candidate_indexes and normalized_line in repeated_normalized_lines:
            removed_lines.append(line.strip())
            continue
        kept_lines.append(line)

    return HeaderFooterRemovalResult(
        text="\n".join(kept_lines),
        removed_count=len(removed_lines),
        removed_lines=removed_lines,
    )


@dataclass(frozen=True)
class _EdgeCandidate:
    """Internal edge-line candidate before repeat counting."""

    line: str
    normalized_line: str
    position: str


def _collect_edge_candidates(page_text: str, *, top_lines: int, bottom_lines: int) -> list[_EdgeCandidate]:
    """Collect normalized non-blank lines from the top and bottom page edges."""

    nonblank_lines = [line.strip() for line in page_text.split("\n") if line.strip()]
    top_candidates = [("top", line) for line in nonblank_lines[:top_lines]]
    bottom_candidates = [("bottom", line) for line in nonblank_lines[-bottom_lines:]]

    candidates: list[_EdgeCandidate] = []
    seen: set[str] = set()
    for position, line in [*top_candidates, *bottom_candidates]:
        normalized_line = _normalize_candidate_line(line)
        if not normalized_line or normalized_line in seen:
            continue
        seen.add(normalized_line)
        candidates.append(_EdgeCandidate(line=line, normalized_line=normalized_line, position=position))

    return candidates


def _candidate_edge_indexes(lines: list[str], *, top_lines: int, bottom_lines: int) -> set[int]:
    """Return indexes of non-blank lines near top/bottom page edges."""

    nonblank_indexes = [index for index, line in enumerate(lines) if line.strip()]
    return set(nonblank_indexes[:top_lines]) | set(nonblank_indexes[-bottom_lines:])


def _normalize_candidate_line(line: str) -> str:
    """Normalize an edge line for repeat comparison."""

    normalized = re.sub(r"\s+", " ", line.strip()).casefold()
    if len(normalized) < 3 or len(normalized) > MAX_CANDIDATE_CHARS:
        return ""
    return normalized


def _minimum_repeat_count(total_pages: int, min_repeat_ratio: float) -> int:
    """Convert a repeat ratio into a page-count threshold."""

    if total_pages <= 0:
        return 0
    ratio = min(1.0, max(0.0, min_repeat_ratio))
    return max(2, math.ceil(total_pages * ratio))
