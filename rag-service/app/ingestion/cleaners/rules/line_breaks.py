"""Repair conservative paragraph line wraps from PDF extraction.

PDF parsers often keep visual line breaks inside normal paragraphs:

```
The RAG system retrieves
relevant chunks from vector
database.
```

This rule only merges paragraph-to-paragraph continuations. It deliberately
does not touch headings, lists, tables, code blocks, captions, or blank-line
boundaries.
"""

from __future__ import annotations

from dataclasses import dataclass
import re

from app.ingestion.cleaners.rules.structure import LineType, classify_pdf_lines


STRONG_SENTENCE_END_RE = re.compile(r"[.!?:;。！？：；]\s*$")
CONTINUATION_START_RE = re.compile(r"^(?:[a-zà-ỹ]|\d|\([a-zà-ỹ])")
MIN_CURRENT_LINE_CHARS = 12


@dataclass(frozen=True)
class ParagraphLineBreakFixResult:
    """Result of repairing paragraph line wraps for one page."""

    text: str
    fixed_count: int


def fix_paragraph_line_breaks(text: str) -> ParagraphLineBreakFixResult:
    """Merge safe paragraph line wraps into natural paragraph lines."""

    if not text:
        return ParagraphLineBreakFixResult(text=text, fixed_count=0)

    lines = text.split("\n")
    classifications = classify_pdf_lines(text)
    output_lines: list[str] = []
    fixed_count = 0
    index = 0

    while index < len(lines):
        current_line = lines[index]
        current_type = classifications[index].line_type
        index += 1

        while index < len(lines) and _can_merge_with_next(
            current_line=current_line,
            current_type=current_type,
            next_line=lines[index],
            next_type=classifications[index].line_type,
        ):
            current_line = _merge_paragraph_lines(current_line, lines[index])
            fixed_count += 1
            index += 1

        output_lines.append(current_line)

    return ParagraphLineBreakFixResult(text="\n".join(output_lines), fixed_count=fixed_count)


def _can_merge_with_next(
    *,
    current_line: str,
    current_type: LineType,
    next_line: str,
    next_type: LineType,
) -> bool:
    """Return whether two neighboring lines are a safe paragraph wrap."""

    current = current_line.strip()
    next_text = next_line.lstrip()

    if current_type != LineType.PARAGRAPH or next_type != LineType.PARAGRAPH:
        return False
    if len(current) < MIN_CURRENT_LINE_CHARS:
        return False
    if current.endswith("-"):
        return False
    if STRONG_SENTENCE_END_RE.search(current):
        return False
    if not CONTINUATION_START_RE.match(next_text):
        return False

    return True


def _merge_paragraph_lines(current_line: str, next_line: str) -> str:
    """Join two paragraph lines with exactly one space."""

    return f"{current_line.rstrip()} {next_line.lstrip()}"
