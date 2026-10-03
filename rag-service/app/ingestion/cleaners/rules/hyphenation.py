"""Repair conservative PDF line-end hyphenation.

PDF text extraction often preserves visual line wraps. A word may be split as:

```
informa-
tion retrieval
```

This rule only joins very clear paragraph-to-paragraph cases. It avoids lists,
tables, code, captions, and headings by relying on the structure classifier.
"""

from __future__ import annotations

from dataclasses import dataclass
import re

from app.ingestion.cleaners.rules.structure import LineType, classify_pdf_lines


HYPHENATED_LINE_RE = re.compile(r"(?P<prefix>.*[A-Za-zÀ-ỹ])-+$")
LOWERCASE_START_RE = re.compile(r"^[a-zà-ỹ]")


@dataclass(frozen=True)
class HyphenationFixResult:
    """Result of repairing line-end hyphenation for one page."""

    text: str
    fixed_count: int


def fix_line_end_hyphenation(text: str) -> HyphenationFixResult:
    """Join words split by a trailing hyphen across two paragraph lines."""

    if not text:
        return HyphenationFixResult(text=text, fixed_count=0)

    lines = text.split("\n")
    classifications = classify_pdf_lines(text)
    output_lines: list[str] = []
    fixed_count = 0
    index = 0

    while index < len(lines):
        current_line = lines[index]

        if _can_join_with_next(lines, classifications, index):
            next_line = lines[index + 1]
            output_lines.append(_join_hyphenated_lines(current_line, next_line))
            fixed_count += 1
            index += 2
            continue

        output_lines.append(current_line)
        index += 1

    return HyphenationFixResult(text="\n".join(output_lines), fixed_count=fixed_count)


def _can_join_with_next(lines: list[str], classifications: list, index: int) -> bool:
    """Return whether two neighboring lines are a safe hyphenation repair."""

    if index + 1 >= len(lines):
        return False

    current = lines[index].rstrip()
    next_line = lines[index + 1].lstrip()

    if classifications[index].line_type != LineType.PARAGRAPH:
        return False
    if classifications[index + 1].line_type != LineType.PARAGRAPH:
        return False
    if not HYPHENATED_LINE_RE.match(current):
        return False
    if not LOWERCASE_START_RE.match(next_line):
        return False

    return True


def _join_hyphenated_lines(current_line: str, next_line: str) -> str:
    """Remove the wrap hyphen and append the next line's first segment."""

    return current_line.rstrip().rstrip("-") + next_line.lstrip()
