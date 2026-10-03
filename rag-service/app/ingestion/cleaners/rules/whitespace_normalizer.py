"""Whitespace cleanup rules for parsed PDF text.

PDF parsers often return text with noisy horizontal spacing, mixed line endings,
trailing spaces, tabs, and too many blank lines. This module keeps the rule
small and conservative so we do not accidentally destroy Markdown-like
structure produced by the parser.
"""

from __future__ import annotations

import re

from app.ingestion.cleaners.rules.structure import LineType, classify_pdf_lines


MULTIPLE_SPACES_RE = re.compile(r" {2,}")
PRESERVE_INTERNAL_SPACING_TYPES = {LineType.CODE, LineType.TABLE}


def normalize_pdf_whitespace(
    text: str,
    *,
    collapse_spaces: bool = True,
    max_blank_lines: int = 2,
) -> str:
    """Normalize whitespace while preserving page/Markdown readability.

    The rule intentionally does not join lines, remove page headers/footers, or
    fix hyphenated line breaks. Those are higher-risk cleaning steps and should
    be implemented separately with their own tests.
    """

    if not text:
        return text

    normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
    max_blank_lines = max(0, max_blank_lines)

    raw_lines = normalized.split("\n")
    classifications = classify_pdf_lines(normalized)
    cleaned_lines: list[str] = []
    blank_run = 0

    for raw_line, classification in zip(raw_lines, classifications, strict=True):
        line = raw_line.rstrip(" ")
        if collapse_spaces and classification.line_type not in PRESERVE_INTERNAL_SPACING_TYPES:
            line = _collapse_spaces_preserving_indent(line)

        if not line.strip():
            blank_run += 1
            if blank_run <= max_blank_lines:
                cleaned_lines.append("")
            continue

        blank_run = 0
        cleaned_lines.append(line)

    return "\n".join(_trim_outer_blank_lines(cleaned_lines))


def _collapse_spaces_preserving_indent(line: str) -> str:
    """Collapse repeated spaces in a line without removing leading indentation."""

    leading_space_count = len(line) - len(line.lstrip(" "))
    leading = line[:leading_space_count]
    body = line[leading_space_count:]
    return leading + MULTIPLE_SPACES_RE.sub(" ", body)


def _trim_outer_blank_lines(lines: list[str]) -> list[str]:
    """Remove blank lines around a page while keeping intentional inner spacing."""

    start = 0
    end = len(lines)

    while start < end and not lines[start].strip():
        start += 1

    while end > start and not lines[end - 1].strip():
        end -= 1

    return lines[start:end]
