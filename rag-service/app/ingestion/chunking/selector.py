from app.ingestion.chunking.llama_sentence_strategy import LlamaSentenceChunkingStrategy
from app.ingestion.profiling import (
    MIXED_UNKNOWN_PROFILE,
    NOVEL_NARRATIVE_PROFILE,
    SCANNED_OR_OCR_REQUIRED_PROFILE,
)


def select_chunking_strategy(document_profile: str):
    """Select the chunking strategy for the detected document profile.

    C1-C3 only need a stable default for narrative books and a safe fallback.
    Future profiles can branch here without changing the pipeline.
    """

    if document_profile in {
        NOVEL_NARRATIVE_PROFILE,
        MIXED_UNKNOWN_PROFILE,
        SCANNED_OR_OCR_REQUIRED_PROFILE,
    }:
        return LlamaSentenceChunkingStrategy()

    return LlamaSentenceChunkingStrategy()
