from app.ingestion.chunking.llama_sentence_strategy import LlamaSentenceChunkingStrategy
from app.ingestion.chunking.chapter_aware_strategy import ChapterAwareChunkingStrategy
from app.ingestion.profiling import (
    MIXED_UNKNOWN_PROFILE,
    NOVEL_NARRATIVE_PROFILE,
    SCANNED_OR_OCR_REQUIRED_PROFILE,
)


def select_chunking_strategy(document_profile: str, *, strategy_version: str = "v1"):
    """Select the chunking strategy for the detected document profile.

    v2 is explicitly opt-in; v1 remains the default and comparison baseline.
    The v2 rules target chapter-labelled narrative, not arbitrary section trees.
    """

    if strategy_version == "v2":
        return ChapterAwareChunkingStrategy()
    if strategy_version != "v1":
        raise ValueError(f"Unsupported chunking strategy version: {strategy_version}")
    if document_profile in {
        NOVEL_NARRATIVE_PROFILE,
        MIXED_UNKNOWN_PROFILE,
        SCANNED_OR_OCR_REQUIRED_PROFILE,
    }:
        return LlamaSentenceChunkingStrategy()

    return LlamaSentenceChunkingStrategy()
