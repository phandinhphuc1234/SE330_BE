"""Conservative, offset-preserving heading detection for narrative v2.

Unlike the v1 page-label detector, scan every line and return boundaries rather
than assigning a single chapter to an entire page. No source text is rewritten.
"""

from __future__ import annotations

from dataclasses import dataclass
import re


_NUMBER = (
    r"[0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten"
    r"|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty"
    r"|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth"
)
_NUMBERED = re.compile(
    r"^(?:chương|chuong|phần|phan|hồi|hoi|quyển|quyen|tập|tap"
    r"|chapter|chap\.?|part|book|volume|vol\.?|act)\s+"
    r"(?P<number>" + _NUMBER + r")(?P<suffix>.*)$", re.IGNORECASE,
)
_UNNUMBERED = re.compile(
    r"^(?P<number>prologue|epilogue|preface|foreword|afterword|introduction|intro|conclusion|appendix)"
    r"(?P<suffix>.*)$", re.IGNORECASE,
)
_FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")
_MARKDOWN = re.compile(r"^#{1,6}\s+")
_TITLE_SEPARATOR = re.compile(r"^\s*[:.\-–—]\s*\S")


@dataclass(frozen=True)
class ChapterBoundary:
    """Heading location in the cleaned page, using Python character offsets."""

    offset: int
    title: str
    number: str


FenceState = tuple[str, int] | None


def find_chapter_boundaries(
    text: str, *, fence_state: FenceState = None,
) -> tuple[list[ChapterBoundary], FenceState]:
    """Scan all lines; carry fenced-code state across contiguous source pages."""

    boundaries: list[ChapterBoundary] = []
    offset = 0
    for line in text.splitlines(keepends=True):
        fence = _FENCE.match(line)
        if fence:
            marker = fence.group(1)
            if fence_state is None:
                fence_state = (marker[0], len(marker))
            elif (marker[0] == fence_state[0] and len(marker) >= fence_state[1]
                  and not line[fence.end():].strip()):
                fence_state = None
        elif fence_state is None:
            heading = _parse_heading(line)
            if heading is not None:
                title, number = heading
                boundaries.append(ChapterBoundary(offset=offset, title=title, number=number))
        offset += len(line)
    return boundaries, fence_state


def _parse_heading(line: str) -> tuple[str, str] | None:
    # Indented code, lists, tables, blockquotes and quoted dialogue cannot start
    # chapters. TOC dotted leaders remain source text, not chapter boundaries.
    if len(line) - len(line.lstrip(" \t")) >= 4 or line.startswith("\t"):
        return None
    title = line.strip()
    if not title or title.startswith(("|", ">", "- ", "* ", "+ ", '"', "“", "'")):
        return None
    explicit_markup = bool(_MARKDOWN.match(title))
    title = _MARKDOWN.sub("", title).rstrip("#").strip()
    if title.startswith("**") and title.endswith("**"):
        explicit_markup = True
        title = title[2:-2].strip()
    title = " ".join(title.split())
    if len(title) > 120 or re.search(r"\.{2,}|…", title):
        return None
    match = _NUMBERED.fullmatch(title) or _UNNUMBERED.fullmatch(title)
    if match is None:
        return None
    suffix = match.group("suffix")
    # Prevent partial number matches (Chapter IVory, Chapter 1st) and narrative
    # sentences such as "Chapter 2 explains the problem". Bare titles with no
    # separator require explicit Markdown heading/bold markup.
    if suffix and not suffix[0].isspace() and suffix[0] not in ":.-–—":
        return None
    if suffix.strip():
        # Printed books commonly use "CHAPTER I." and "APPENDIX.". A single
        # terminal period is heading punctuation, not a missing subtitle.
        # Keep it in the metadata title and leave source text/offsets intact.
        # Dotted leaders/ellipsis were rejected above, so TOC lines do not
        # become boundaries merely because periods are supported here.
        terminal_period = suffix.strip() == "."
        if not (terminal_period or _TITLE_SEPARATOR.match(suffix)
                or (explicit_markup and suffix[0].isspace())):
            return None
        if title.endswith(("?", "!")):
            return None
    return title, match.group("number")
