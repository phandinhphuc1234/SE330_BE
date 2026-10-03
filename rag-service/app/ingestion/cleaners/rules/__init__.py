from app.ingestion.cleaners.rules.header_footer import (
    HeaderFooterCandidate,
    HeaderFooterDetectionResult,
    HeaderFooterRemovalResult,
    detect_repeated_header_footer,
    remove_repeated_header_footer,
)
from app.ingestion.cleaners.rules.hyphenation import HyphenationFixResult, fix_line_end_hyphenation
from app.ingestion.cleaners.rules.line_breaks import ParagraphLineBreakFixResult, fix_paragraph_line_breaks
from app.ingestion.cleaners.rules.page_number_remover import PageNumberRemovalResult, remove_page_numbers
from app.ingestion.cleaners.rules.structure import (
    LineClassification,
    LineType,
    classify_pdf_line,
    classify_pdf_lines,
    count_pdf_line_types,
    has_structural_lines,
)
from app.ingestion.cleaners.rules.unicode_normalizer import normalize_pdf_unicode
from app.ingestion.cleaners.rules.whitespace_normalizer import normalize_pdf_whitespace

__all__ = [
    "LineClassification",
    "LineType",
    "HeaderFooterCandidate",
    "HeaderFooterDetectionResult",
    "HeaderFooterRemovalResult",
    "HyphenationFixResult",
    "ParagraphLineBreakFixResult",
    "PageNumberRemovalResult",
    "classify_pdf_line",
    "classify_pdf_lines",
    "count_pdf_line_types",
    "detect_repeated_header_footer",
    "has_structural_lines",
    "fix_line_end_hyphenation",
    "fix_paragraph_line_breaks",
    "normalize_pdf_unicode",
    "normalize_pdf_whitespace",
    "remove_page_numbers",
    "remove_repeated_header_footer",
]
