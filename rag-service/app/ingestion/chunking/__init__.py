"""Chunking strategies for ingestion.

This package owns strategy selection and metadata. Low-level splitting still
delegates to LlamaIndex adapters.
"""

from app.ingestion.chunking.llama_sentence_strategy import (
    LLAMA_SENTENCE_CHUNKING_STRATEGY,
    NARRATIVE_CHUNKING_STRATEGY,
    NARRATIVE_CHUNKING_STRATEGY_VERSION,
    LlamaSentenceChunkingStrategy,
)
from app.ingestion.chunking.chapter_detector import (
    CHAPTER_DETECTION_SOURCE,
    CHAPTER_DETECTION_VERSION,
    ChapterDetector,
    ChapterMatch,
)
from app.ingestion.chunking.quality import (
    ChunkQualityIssue,
    ChunkQualityReport,
    ChunkQualityValidator,
    attach_chunk_quality_report,
)
from app.ingestion.chunking.selector import select_chunking_strategy
from app.ingestion.chunking.chapter_aware_strategy import ChapterAwareChunkingStrategy
from app.ingestion.chunking.strategy import ChunkingStrategy
from app.ingestion.chunking.token_counter import (
    APPROX_TOKEN_COUNTER,
    ApproxTokenCounter,
    TokenCounter,
    attach_token_counts,
)

__all__ = [
    "LLAMA_SENTENCE_CHUNKING_STRATEGY",
    "NARRATIVE_CHUNKING_STRATEGY",
    "NARRATIVE_CHUNKING_STRATEGY_VERSION",
    "CHAPTER_DETECTION_SOURCE",
    "CHAPTER_DETECTION_VERSION",
    "ChapterDetector",
    "ChapterAwareChunkingStrategy",
    "ChapterMatch",
    "ChunkQualityIssue",
    "ChunkQualityReport",
    "ChunkQualityValidator",
    "ChunkingStrategy",
    "LlamaSentenceChunkingStrategy",
    "APPROX_TOKEN_COUNTER",
    "ApproxTokenCounter",
    "TokenCounter",
    "attach_chunk_quality_report",
    "attach_token_counts",
    "select_chunking_strategy",
]
