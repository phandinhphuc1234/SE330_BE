from dataclasses import dataclass, field
from typing import Any, Literal

from app.ingestion.chunkers.base import Chunk

ChunkQualitySeverity = Literal["error", "warning"]
ChunkQualityStatus = Literal["PASS", "FAIL"]

REQUIRED_CHUNK_METADATA_KEYS = (
    "document_id",
    "documentId",
    "chunk_hash",
    "chunking_strategy",
    "chunking_strategy_version",
    "chunk_level",
    "pageStart",
    "pageEnd",
    "vector_id",
    "token_count",
    "token_counter",
)


@dataclass(frozen=True)
class ChunkQualityIssue:
    """One quality issue found after chunking and before embedding/indexing."""

    code: str
    message: str
    severity: ChunkQualitySeverity
    chunk_index: int | None = None
    details: dict[str, Any] = field(default_factory=dict)

    def to_metadata(self) -> dict[str, Any]:
        """Serialize issue into JSON-friendly metadata."""

        payload: dict[str, Any] = {
            "code": self.code,
            "message": self.message,
            "severity": self.severity,
        }
        if self.chunk_index is not None:
            payload["chunk_index"] = self.chunk_index
        if self.details:
            payload["details"] = dict(self.details)
        return payload


@dataclass(frozen=True)
class ChunkQualityReport:
    """Quality report for the chunk set produced by one document ingestion."""

    status: ChunkQualityStatus
    chunk_count: int
    min_chunk_chars: int
    max_chunk_chars: int
    avg_chunk_chars: float
    min_chunk_tokens: int
    max_chunk_tokens: int
    avg_chunk_tokens: float
    duplicate_chunk_ratio: float
    errors: list[ChunkQualityIssue] = field(default_factory=list)
    warnings: list[ChunkQualityIssue] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.status == "PASS"

    def to_metadata(self) -> dict[str, Any]:
        """Serialize report into JSON-friendly metadata."""

        return {
            "status": self.status,
            "chunk_count": self.chunk_count,
            "min_chunk_chars": self.min_chunk_chars,
            "max_chunk_chars": self.max_chunk_chars,
            "avg_chunk_chars": self.avg_chunk_chars,
            "min_chunk_tokens": self.min_chunk_tokens,
            "max_chunk_tokens": self.max_chunk_tokens,
            "avg_chunk_tokens": self.avg_chunk_tokens,
            "duplicate_chunk_ratio": self.duplicate_chunk_ratio,
            "error_count": len(self.errors),
            "warning_count": len(self.warnings),
            "errors": [issue.to_metadata() for issue in self.errors],
            "warnings": [issue.to_metadata() for issue in self.warnings],
        }


class ChunkQualityValidator:
    """Validate chunks before they are persisted/embedded.

    C4 intentionally keeps the policy conservative:
    - empty/missing metadata/oversized/page range errors fail ingestion;
    - short chunks are warnings so MVP narrative books are not rejected too early;
    - duplicate ratio only fails for meaningful chunk counts.
    """

    def __init__(
        self,
        *,
        min_chunk_chars_warning: int = 40,
        max_chunk_chars: int = 20_000,
        max_duplicate_ratio: float = 0.3,
        min_chunk_count_for_duplicate_failure: int = 5,
        required_metadata_keys: tuple[str, ...] = REQUIRED_CHUNK_METADATA_KEYS,
    ) -> None:
        self.min_chunk_chars_warning = min_chunk_chars_warning
        self.max_chunk_chars = max_chunk_chars
        self.max_duplicate_ratio = max_duplicate_ratio
        self.min_chunk_count_for_duplicate_failure = min_chunk_count_for_duplicate_failure
        self.required_metadata_keys = required_metadata_keys

    def validate(self, chunks: list[Chunk]) -> ChunkQualityReport:
        """Return a quality report. Any error issue makes the report fail."""

        errors: list[ChunkQualityIssue] = []
        warnings: list[ChunkQualityIssue] = []
        lengths = [len(chunk.text.strip()) for chunk in chunks]
        token_counts: list[int] = []

        if not chunks:
            errors.append(
                ChunkQualityIssue(
                    code="CHUNKS_EMPTY",
                    message="Chunker produced no chunks.",
                    severity="error",
                )
            )

        for index, chunk in enumerate(chunks):
            text = chunk.text.strip()
            metadata = chunk.metadata or {}
            token_count = _safe_int(metadata.get("token_count"))
            if token_count is not None and token_count >= 0:
                token_counts.append(token_count)

            if not text:
                errors.append(
                    ChunkQualityIssue(
                        code="CHUNK_EMPTY",
                        message="Chunk text is empty.",
                        severity="error",
                        chunk_index=index,
                    )
                )
            elif len(text) < self.min_chunk_chars_warning:
                warnings.append(
                    ChunkQualityIssue(
                        code="CHUNK_TOO_SHORT",
                        message="Chunk text is very short and may lack retrieval context.",
                        severity="warning",
                        chunk_index=index,
                        details={"chunk_chars": len(text), "min_warning_chars": self.min_chunk_chars_warning},
                    )
                )

            if len(text) > self.max_chunk_chars:
                errors.append(
                    ChunkQualityIssue(
                        code="CHUNK_TOO_LONG",
                        message="Chunk text exceeds the configured safety limit.",
                        severity="error",
                        chunk_index=index,
                        details={"chunk_chars": len(text), "max_chunk_chars": self.max_chunk_chars},
                    )
                )

            missing_keys = [key for key in self.required_metadata_keys if metadata.get(key) in (None, "")]
            if missing_keys:
                errors.append(
                    ChunkQualityIssue(
                        code="CHUNK_METADATA_MISSING",
                        message="Chunk is missing required metadata.",
                        severity="error",
                        chunk_index=index,
                        details={"missing_keys": missing_keys},
                    )
                )

            if metadata.get("token_count") not in (None, "") and token_count is None:
                errors.append(
                    ChunkQualityIssue(
                        code="CHUNK_TOKEN_COUNT_INVALID",
                        message="Chunk token_count metadata must be an integer.",
                        severity="error",
                        chunk_index=index,
                        details={"token_count": metadata.get("token_count")},
                    )
                )
            elif token_count is not None and text and token_count <= 0:
                errors.append(
                    ChunkQualityIssue(
                        code="CHUNK_TOKEN_COUNT_INVALID",
                        message="Non-empty chunk has a non-positive token_count.",
                        severity="error",
                        chunk_index=index,
                        details={"token_count": token_count},
                    )
                )

            page_start = metadata.get("pageStart")
            page_end = metadata.get("pageEnd")
            if isinstance(page_start, int) and isinstance(page_end, int) and page_start > page_end:
                errors.append(
                    ChunkQualityIssue(
                        code="CHUNK_PAGE_RANGE_INVALID",
                        message="Chunk pageStart is greater than pageEnd.",
                        severity="error",
                        chunk_index=index,
                        details={"pageStart": page_start, "pageEnd": page_end},
                    )
                )

        duplicate_ratio = self._duplicate_ratio(chunks)
        if duplicate_ratio > 0:
            warnings.append(
                ChunkQualityIssue(
                    code="CHUNK_DUPLICATES_DETECTED",
                    message="Duplicate chunk text was detected.",
                    severity="warning",
                    details={"duplicate_chunk_ratio": duplicate_ratio},
                )
            )
        if len(chunks) >= self.min_chunk_count_for_duplicate_failure and duplicate_ratio > self.max_duplicate_ratio:
            errors.append(
                ChunkQualityIssue(
                    code="CHUNK_DUPLICATE_RATIO_TOO_HIGH",
                    message="Duplicate chunk ratio is too high.",
                    severity="error",
                    details={
                        "duplicate_chunk_ratio": duplicate_ratio,
                        "max_duplicate_ratio": self.max_duplicate_ratio,
                    },
                )
            )

        return ChunkQualityReport(
            status="FAIL" if errors else "PASS",
            chunk_count=len(chunks),
            min_chunk_chars=min(lengths) if lengths else 0,
            max_chunk_chars=max(lengths) if lengths else 0,
            avg_chunk_chars=round(sum(lengths) / len(lengths), 2) if lengths else 0.0,
            min_chunk_tokens=min(token_counts) if token_counts else 0,
            max_chunk_tokens=max(token_counts) if token_counts else 0,
            avg_chunk_tokens=round(sum(token_counts) / len(token_counts), 2) if token_counts else 0.0,
            duplicate_chunk_ratio=duplicate_ratio,
            errors=errors,
            warnings=warnings,
        )

    @staticmethod
    def _duplicate_ratio(chunks: list[Chunk]) -> float:
        if not chunks:
            return 0.0

        normalized_texts = [_normalize_text_for_duplicate_check(chunk.text) for chunk in chunks]
        non_empty_texts = [text for text in normalized_texts if text]
        if not non_empty_texts:
            return 0.0

        duplicate_count = len(non_empty_texts) - len(set(non_empty_texts))
        return round(duplicate_count / len(non_empty_texts), 4)


def attach_chunk_quality_report(chunks: list[Chunk], report: ChunkQualityReport) -> None:
    """Attach report summary to every chunk metadata in-place."""

    report_metadata = report.to_metadata()
    for chunk in chunks:
        chunk.metadata["chunk_quality_status"] = report.status
        chunk.metadata["chunk_quality_report"] = report_metadata


def _normalize_text_for_duplicate_check(text: str) -> str:
    return " ".join((text or "").casefold().split())


def _safe_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None
