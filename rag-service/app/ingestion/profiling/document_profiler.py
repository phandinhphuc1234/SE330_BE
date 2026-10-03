import re
from collections.abc import Sequence

from llama_index.core import Document

from app.ingestion.profiling.models import (
    MIXED_UNKNOWN_PROFILE,
    NOVEL_NARRATIVE_PROFILE,
    SCANNED_OR_OCR_REQUIRED_PROFILE,
    DocumentProfile,
)

CHAPTER_HEADING_PATTERN = re.compile(
    r"^\s*(?:chương|chuong|chapter|phần|phan|hồi|hoi)\s+([0-9ivxlcdm]+)\b",
    re.IGNORECASE | re.MULTILINE,
)
CODE_PATTERN = re.compile(
    r"(```|^\s*(?:def|class|import|from|public|private|function|const|let|var)\s+)",
    re.IGNORECASE | re.MULTILINE,
)
QA_PATTERN = re.compile(
    r"(^\s*(?:q|question|câu hỏi|cau hoi)\s*[:.]|^\s*(?:a|answer|đáp án|dap an)\s*[:.])",
    re.IGNORECASE | re.MULTILINE,
)
MATH_SYMBOL_PATTERN = re.compile(r"[∑√∫≈≠≤≥±∞÷×]|\\(?:frac|sum|int|sqrt)\b")
MARKDOWN_TABLE_PATTERN = re.compile(r"^\s*\|.+\|\s*$", re.MULTILINE)
PARAGRAPH_SPLIT_PATTERN = re.compile(r"\n\s*\n+")


class DocumentProfiler:
    """Classify cleaned documents with cheap, deterministic rules.

    The first implementation only needs enough signal for the current roadmap:
    identify normal narrative/novel text, flag OCR-like low-text inputs, and
    fall back to mixed_unknown for table/code/Q&A/math-heavy documents.
    """

    def __init__(
        self,
        *,
        min_avg_chars_for_text_layer: int = 50,
        narrative_min_avg_chars: int = 120,
        narrative_min_paragraphs_per_page: float = 0.75,
        max_table_lines_for_narrative: int = 3,
        max_code_blocks_for_narrative: int = 1,
        max_qa_patterns_for_narrative: int = 3,
        max_math_symbol_ratio_for_narrative: float = 0.03,
    ) -> None:
        self.min_avg_chars_for_text_layer = min_avg_chars_for_text_layer
        self.narrative_min_avg_chars = narrative_min_avg_chars
        self.narrative_min_paragraphs_per_page = narrative_min_paragraphs_per_page
        self.max_table_lines_for_narrative = max_table_lines_for_narrative
        self.max_code_blocks_for_narrative = max_code_blocks_for_narrative
        self.max_qa_patterns_for_narrative = max_qa_patterns_for_narrative
        self.max_math_symbol_ratio_for_narrative = max_math_symbol_ratio_for_narrative

    def profile(self, documents: Sequence[Document]) -> DocumentProfile:
        """Return the best-effort profile for cleaned page/chapter documents."""

        signals = self._collect_signals(documents)
        if self._looks_like_ocr_required(signals):
            return DocumentProfile(
                name=SCANNED_OR_OCR_REQUIRED_PROFILE,
                confidence=0.9,
                signals=signals,
            )

        if self._looks_like_narrative(signals):
            return DocumentProfile(
                name=NOVEL_NARRATIVE_PROFILE,
                confidence=self._narrative_confidence(signals),
                signals=signals,
            )

        return DocumentProfile(
            name=MIXED_UNKNOWN_PROFILE,
            confidence=0.5,
            signals=signals,
        )

    def _collect_signals(self, documents: Sequence[Document]) -> dict:
        texts = [document.text or "" for document in documents]
        joined_text = "\n\n".join(texts)
        stripped_texts = [text.strip() for text in texts]
        non_empty_texts = [text for text in stripped_texts if text]
        page_count = len(texts)
        text_page_count = len(non_empty_texts)
        total_chars = sum(len(text) for text in non_empty_texts)
        paragraph_count = sum(self._count_paragraphs(text) for text in non_empty_texts)
        math_symbol_count = len(MATH_SYMBOL_PATTERN.findall(joined_text))
        effective_total_chars = max(total_chars, 1)

        table_count_from_metadata = 0
        image_count_from_metadata = 0
        for document in documents:
            metadata = document.metadata or {}
            table_count_from_metadata += _safe_int(metadata.get("table_count"))
            image_count_from_metadata += _safe_int(metadata.get("image_count"))

        markdown_table_lines = len(MARKDOWN_TABLE_PATTERN.findall(joined_text))
        code_block_count = len(CODE_PATTERN.findall(joined_text))
        qa_pattern_count = len(QA_PATTERN.findall(joined_text))
        heading_count = len(CHAPTER_HEADING_PATTERN.findall(joined_text))

        return {
            "page_count": page_count,
            "text_page_count": text_page_count,
            "total_text_chars": total_chars,
            "avg_text_chars_per_page": round(total_chars / max(page_count, 1), 2),
            "paragraph_count": paragraph_count,
            "paragraphs_per_page": round(paragraph_count / max(page_count, 1), 2),
            "heading_count": heading_count,
            "table_count": table_count_from_metadata + markdown_table_lines,
            "metadata_table_count": table_count_from_metadata,
            "markdown_table_lines": markdown_table_lines,
            "image_count": image_count_from_metadata,
            "code_block_count": code_block_count,
            "qa_pattern_count": qa_pattern_count,
            "math_symbol_count": math_symbol_count,
            "math_symbol_ratio": round(math_symbol_count / effective_total_chars, 5),
        }

    def _looks_like_narrative(self, signals: dict) -> bool:
        return (
            signals["avg_text_chars_per_page"] >= self.narrative_min_avg_chars
            and signals["paragraphs_per_page"] >= self.narrative_min_paragraphs_per_page
            and signals["table_count"] <= self.max_table_lines_for_narrative
            and signals["code_block_count"] <= self.max_code_blocks_for_narrative
            and signals["qa_pattern_count"] <= self.max_qa_patterns_for_narrative
            and signals["math_symbol_ratio"] <= self.max_math_symbol_ratio_for_narrative
        )

    def _looks_like_ocr_required(self, signals: dict) -> bool:
        """Detect low-text PDFs without mistaking short structured pages for scans."""

        has_structured_text_signal = (
            signals["heading_count"] > 0
            or signals["table_count"] > 0
            or signals["code_block_count"] > 0
            or signals["qa_pattern_count"] > 0
            or signals["math_symbol_count"] > 0
            or signals["paragraph_count"] > signals["text_page_count"]
        )
        return (
            signals["page_count"] == 0
            or (
                signals["avg_text_chars_per_page"] < self.min_avg_chars_for_text_layer
                and not has_structured_text_signal
            )
        )

    @staticmethod
    def _narrative_confidence(signals: dict) -> float:
        confidence = 0.65
        if signals["paragraphs_per_page"] >= 1.5:
            confidence += 0.1
        if signals["heading_count"] > 0:
            confidence += 0.05
        if signals["table_count"] == 0 and signals["code_block_count"] == 0:
            confidence += 0.1
        return min(round(confidence, 2), 0.95)

    @staticmethod
    def _count_paragraphs(text: str) -> int:
        paragraphs = [part.strip() for part in PARAGRAPH_SPLIT_PATTERN.split(text) if part.strip()]
        if paragraphs:
            return len(paragraphs)
        return 1 if text.strip() else 0


def _safe_int(value) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return max(value, 0)
    return 0
