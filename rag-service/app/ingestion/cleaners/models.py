from dataclasses import dataclass, field


@dataclass(frozen=True)
class CleanedPage:
    """One page after the PDF cleaning stage.

    The cleaner keeps page boundaries intact because chunking and citations
    need to trace text back to the original PDF page.
    """

    page_number: int
    cleaned_text: str
    section_title: str | None = None
    warnings: list[str] = field(default_factory=list)
    metadata: dict = field(default_factory=dict)


@dataclass(frozen=True)
class QualityReport:
    """Minimal quality report produced by the cleaner.

    More metrics will be added step by step. The current version is enough to
    verify that cleaning is page-wise and that downstream chunking can decide
    whether it is safe to continue.
    """

    page_count: int
    cleaning_version: str
    raw_char_count: int
    cleaned_char_count: int
    empty_pages: int
    text_pages_ratio: float
    quality_status: str
    warnings: list[str] = field(default_factory=list)

    @property
    def can_chunk(self) -> bool:
        """Return true when the cleaned output is safe to pass to chunking."""

        return self.quality_status in {"GOOD", "ACCEPTABLE"}


@dataclass(frozen=True)
class CleaningResult:
    """Final output of the PDF cleaner."""

    pages: list[CleanedPage]
    quality_report: QualityReport
