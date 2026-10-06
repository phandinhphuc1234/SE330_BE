from dataclasses import dataclass, field
from typing import Any

DOCUMENT_PROFILE_VERSION = "v1"
NOVEL_NARRATIVE_PROFILE = "novel_narrative"
MIXED_UNKNOWN_PROFILE = "mixed_unknown"
SCANNED_OR_OCR_REQUIRED_PROFILE = "scanned_pdf_or_ocr_required"


@dataclass(frozen=True)
class DocumentProfile:
    """Rule-based document profile used to select a chunking strategy.

    This is intentionally small and explainable. It is not an LLM classifier;
    it records the measured signals so ingestion/debug screens can explain why
    a document was treated as narrative text, mixed text, or OCR-required.
    """

    name: str
    version: str = DOCUMENT_PROFILE_VERSION
    confidence: float = 0.0
    signals: dict[str, Any] = field(default_factory=dict)

    def to_metadata(self) -> dict[str, Any]:
        """Return the metadata shape persisted on documents/chunks."""

        return {
            "document_profile": self.name,
            "document_profile_version": self.version,
            "document_profile_confidence": self.confidence,
            "document_profile_signals": dict(self.signals),
        }
