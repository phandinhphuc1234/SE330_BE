from collections.abc import Sequence
from dataclasses import dataclass

from app.core.config import get_settings
from app.ingestion.cleaners.models import CleanedPage, CleaningResult, QualityReport
from app.ingestion.cleaners.rules import (
    HeaderFooterDetectionResult,
    HeaderFooterRemovalResult,
    HyphenationFixResult,
    ParagraphLineBreakFixResult,
    PageNumberRemovalResult,
    count_pdf_line_types,
    detect_repeated_header_footer,
    fix_line_end_hyphenation,
    fix_paragraph_line_breaks,
    has_structural_lines,
    normalize_pdf_unicode,
    normalize_pdf_whitespace,
    remove_page_numbers,
    remove_repeated_header_footer,
)
from app.ingestion.parsers.base import ParsedDocument


class PdfCleaner:
    """Page-wise PDF cleaner.

    The cleaner keeps parser page boundaries and applies small text-normalizing
    rules one by one. This makes the pipeline easier to inspect than a single
    large "clean everything" function.
    """

    def __init__(
        self,
        *,
        cleaning_version: str | None = None,
        normalize_unicode: bool | None = None,
        collapse_spaces: bool | None = None,
        max_blank_lines: int | None = None,
        remove_page_numbers_enabled: bool = True,
        page_number_scan_lines: int = 2,
        fix_hyphenation_enabled: bool = True,
        fix_line_breaks_enabled: bool = True,
        header_footer_detection_enabled: bool | None = None,
        header_footer_top_lines: int | None = None,
        header_footer_bottom_lines: int | None = None,
        header_footer_min_repeat_ratio: float | None = None,
        header_footer_removal_enabled: bool | None = None,
    ) -> None:
        settings = get_settings()
        self.cleaning_version = cleaning_version or settings.pdf_cleaning_version
        self.normalize_unicode = (
            settings.pdf_clean_normalize_unicode if normalize_unicode is None else normalize_unicode
        )
        self.collapse_spaces = settings.pdf_clean_collapse_spaces if collapse_spaces is None else collapse_spaces
        self.max_blank_lines = settings.pdf_clean_max_blank_lines if max_blank_lines is None else max_blank_lines
        self.remove_page_numbers_enabled = remove_page_numbers_enabled
        self.page_number_scan_lines = page_number_scan_lines
        self.fix_hyphenation_enabled = fix_hyphenation_enabled
        self.fix_line_breaks_enabled = fix_line_breaks_enabled
        self.header_footer_detection_enabled = (
            settings.pdf_clean_header_footer_detection_enabled
            if header_footer_detection_enabled is None
            else header_footer_detection_enabled
        )
        self.header_footer_top_lines = (
            settings.pdf_clean_header_footer_top_lines if header_footer_top_lines is None else header_footer_top_lines
        )
        self.header_footer_bottom_lines = (
            settings.pdf_clean_header_footer_bottom_lines
            if header_footer_bottom_lines is None
            else header_footer_bottom_lines
        )
        self.header_footer_min_repeat_ratio = (
            settings.pdf_clean_header_footer_min_repeat_ratio
            if header_footer_min_repeat_ratio is None
            else header_footer_min_repeat_ratio
        )
        self.header_footer_removal_enabled = (
            settings.pdf_clean_header_footer_removal_enabled
            if header_footer_removal_enabled is None
            else header_footer_removal_enabled
        )

    def clean(self, parsed_pages: Sequence[ParsedDocument]) -> CleaningResult:
        """Convert parsed page units into cleaned page units plus a quality report."""

        drafts: list[_CleanedPageDraft] = []
        pages: list[CleanedPage] = []
        warnings: list[str] = []

        for fallback_index, parsed_page in enumerate(parsed_pages, start=1):
            page_number = self._resolve_page_number(parsed_page, fallback_index)
            text_cleaning = self._clean_page_text_before_document_rules(parsed_page.text)

            drafts.append(
                _CleanedPageDraft(
                    page_number=page_number,
                    parsed_metadata=parsed_page.metadata or {},
                    text_cleaning=text_cleaning,
                )
            )

        header_footer_detection = self._detect_header_footer(drafts)

        for index, draft in enumerate(drafts):
            header_footer_candidates = header_footer_detection.page_candidates[index]
            text_cleaning = self._apply_document_and_late_rules(draft.text_cleaning, header_footer_candidates)
            cleaned_text = text_cleaning.text
            page_warnings: list[str] = []
            if not cleaned_text.strip():
                page_warnings.append("EMPTY_PAGE_TEXT")
            line_type_counts = count_pdf_line_types(cleaned_text)

            metadata = {
                **draft.parsed_metadata,
                "cleaning_stage": "contract",
                "cleaning_version": self.cleaning_version,
                "unicode_normalized": self.normalize_unicode,
                "spaces_collapsed": self.collapse_spaces,
                "max_blank_lines": self.max_blank_lines,
                "page_number_removal_enabled": self.remove_page_numbers_enabled,
                "page_number_scan_lines": self.page_number_scan_lines,
                "removed_page_number_count": draft.text_cleaning.page_number_removal.removed_count,
                "removed_page_number_lines": draft.text_cleaning.page_number_removal.removed_lines,
                "hyphenation_fix_enabled": self.fix_hyphenation_enabled,
                "fixed_hyphenation_count": text_cleaning.hyphenation_fix.fixed_count,
                "line_break_fix_enabled": self.fix_line_breaks_enabled,
                "fixed_line_break_count": text_cleaning.line_break_fix.fixed_count,
                "header_footer_detection_enabled": self.header_footer_detection_enabled,
                "header_footer_top_lines": self.header_footer_top_lines,
                "header_footer_bottom_lines": self.header_footer_bottom_lines,
                "header_footer_min_repeat_ratio": self.header_footer_min_repeat_ratio,
                "header_footer_min_repeat_count": header_footer_detection.min_repeat_count,
                "header_footer_removal_enabled": self.header_footer_removal_enabled,
                "repeated_header_footer_candidate_count": len(header_footer_candidates),
                "repeated_header_footer_candidate_lines": [candidate.line for candidate in header_footer_candidates],
                "repeated_header_footer_candidates": [
                    {
                        "line": candidate.line,
                        "position": candidate.position,
                        "occurrence_count": candidate.occurrence_count,
                    }
                    for candidate in header_footer_candidates
                ],
                "removed_header_footer_count": text_cleaning.header_footer_removal.removed_count,
                "removed_header_footer_lines": text_cleaning.header_footer_removal.removed_lines,
                "line_count": sum(line_type_counts.values()),
                "line_type_counts": line_type_counts,
                "has_structural_blocks": has_structural_lines(line_type_counts),
            }
            pages.append(
                CleanedPage(
                    page_number=draft.page_number,
                    cleaned_text=cleaned_text,
                    section_title=None,
                    warnings=page_warnings,
                    metadata=metadata,
                )
            )
            warnings.extend(f"page_{draft.page_number}:{warning}" for warning in page_warnings)

        quality_report = self._build_quality_report(pages, parsed_pages, warnings)
        return CleaningResult(pages=pages, quality_report=quality_report)

    def _clean_page_text_before_document_rules(
        self,
        text: str,
    ) -> "_PageTextCleaningResult":
        """Apply per-page rules that must run before document-level detection."""

        if self.normalize_unicode:
            text = normalize_pdf_unicode(text)
        text = normalize_pdf_whitespace(
            text,
            collapse_spaces=self.collapse_spaces,
            max_blank_lines=self.max_blank_lines,
        )
        page_number_removal = PageNumberRemovalResult(text=text, removed_count=0, removed_lines=[])
        if self.remove_page_numbers_enabled:
            page_number_removal = remove_page_numbers(text, scan_lines=self.page_number_scan_lines)
            text = normalize_pdf_whitespace(
                page_number_removal.text,
                collapse_spaces=self.collapse_spaces,
                max_blank_lines=self.max_blank_lines,
            )

        return _PageTextCleaningResult(
            text=text,
            header_footer_scan_text=text,
            page_number_removal=page_number_removal,
            header_footer_removal=HeaderFooterRemovalResult(text=text, removed_count=0, removed_lines=[]),
            hyphenation_fix=HyphenationFixResult(text=text, fixed_count=0),
            line_break_fix=ParagraphLineBreakFixResult(text=text, fixed_count=0),
        )

    def _apply_document_and_late_rules(
        self,
        text_cleaning: "_PageTextCleaningResult",
        header_footer_candidates: Sequence,
    ) -> "_PageTextCleaningResult":
        """Apply document-level removal, then late paragraph-level repairs."""

        text = text_cleaning.text
        header_footer_removal = HeaderFooterRemovalResult(text=text, removed_count=0, removed_lines=[])
        if self.header_footer_removal_enabled:
            header_footer_removal = remove_repeated_header_footer(
                text,
                header_footer_candidates,
                top_lines=self.header_footer_top_lines,
                bottom_lines=self.header_footer_bottom_lines,
            )
            text = normalize_pdf_whitespace(
                header_footer_removal.text,
                collapse_spaces=self.collapse_spaces,
                max_blank_lines=self.max_blank_lines,
            )

        hyphenation_fix = HyphenationFixResult(text=text, fixed_count=0)
        if self.fix_hyphenation_enabled:
            hyphenation_fix = fix_line_end_hyphenation(text)
            text = hyphenation_fix.text

        line_break_fix = ParagraphLineBreakFixResult(text=text, fixed_count=0)
        if self.fix_line_breaks_enabled:
            line_break_fix = fix_paragraph_line_breaks(text)
            text = line_break_fix.text

        return _PageTextCleaningResult(
            text=text,
            header_footer_scan_text=text_cleaning.header_footer_scan_text,
            page_number_removal=text_cleaning.page_number_removal,
            header_footer_removal=header_footer_removal,
            hyphenation_fix=hyphenation_fix,
            line_break_fix=line_break_fix,
        )

    def _detect_header_footer(self, drafts: Sequence["_CleanedPageDraft"]) -> HeaderFooterDetectionResult:
        """Detect repeated header/footer candidates across all pages without removing them."""

        if not self.header_footer_detection_enabled:
            return HeaderFooterDetectionResult.empty(len(drafts))

        return detect_repeated_header_footer(
            [draft.text_cleaning.header_footer_scan_text for draft in drafts],
            top_lines=self.header_footer_top_lines,
            bottom_lines=self.header_footer_bottom_lines,
            min_repeat_ratio=self.header_footer_min_repeat_ratio,
        )

    def _resolve_page_number(self, parsed_page: ParsedDocument, fallback_index: int) -> int:
        """Read page number from parser metadata, falling back to sequence order."""

        page_number = (parsed_page.metadata or {}).get("page_number")
        if isinstance(page_number, int) and page_number > 0:
            return page_number
        return fallback_index

    def _build_quality_report(
        self,
        pages: list[CleanedPage],
        parsed_pages: Sequence[ParsedDocument],
        warnings: list[str],
    ) -> QualityReport:
        """Create the minimal quality report for the current cleaner contract."""

        page_count = len(pages)
        raw_char_count = sum(len(page.text) for page in parsed_pages)
        cleaned_char_count = sum(len(page.cleaned_text) for page in pages)
        empty_pages = sum(1 for page in pages if not page.cleaned_text.strip())
        text_pages = page_count - empty_pages
        text_pages_ratio = text_pages / page_count if page_count else 0.0

        quality_status = "GOOD"
        if page_count == 0:
            quality_status = "FAILED"
            warnings = [*warnings, "NO_PARSED_PAGES"]
        elif text_pages == 0:
            quality_status = "FAILED"
            warnings = [*warnings, "NO_CLEANED_TEXT"]
        elif empty_pages:
            quality_status = "ACCEPTABLE"

        return QualityReport(
            page_count=page_count,
            cleaning_version=self.cleaning_version,
            raw_char_count=raw_char_count,
            cleaned_char_count=cleaned_char_count,
            empty_pages=empty_pages,
            text_pages_ratio=round(text_pages_ratio, 4),
            quality_status=quality_status,
            warnings=warnings,
        )


@dataclass(frozen=True)
class _PageTextCleaningResult:
    """Internal per-page text cleaning output before page metadata is built."""

    text: str
    header_footer_scan_text: str
    page_number_removal: PageNumberRemovalResult
    header_footer_removal: HeaderFooterRemovalResult
    hyphenation_fix: HyphenationFixResult
    line_break_fix: ParagraphLineBreakFixResult


@dataclass(frozen=True)
class _CleanedPageDraft:
    """Internal draft used so document-level rules can inspect all pages first."""

    page_number: int
    parsed_metadata: dict
    text_cleaning: _PageTextCleaningResult
