from llama_index.core import Document

from app.ingestion.chunking import (
    NARRATIVE_CHUNKING_STRATEGY,
    NARRATIVE_CHUNKING_STRATEGY_VERSION,
    LlamaSentenceChunkingStrategy,
    select_chunking_strategy,
)
from app.ingestion.profiling import NOVEL_NARRATIVE_PROFILE


def test_llama_sentence_chunking_strategy_adds_narrative_strategy_metadata() -> None:
    strategy = LlamaSentenceChunkingStrategy()
    nodes = strategy.build_nodes(
        [
            Document(
                text=(
                    "Minh bước vào thư viện khi trời vừa tối. "
                    "Ở cuối dãy sách, một cuốn sách màu đen nằm lệch khỏi kệ."
                ),
                metadata={
                    "document_id": 7,
                    "document_profile": NOVEL_NARRATIVE_PROFILE,
                    "page_number": 3,
                },
                id_="page-3",
            )
        ],
        chunk_size=128,
        chunk_overlap=16,
    )

    assert len(nodes) == 1
    metadata = nodes[0].metadata
    assert metadata["document_profile"] == NOVEL_NARRATIVE_PROFILE
    assert metadata["chunking_strategy"] == NARRATIVE_CHUNKING_STRATEGY
    assert metadata["chunking_strategy_version"] == NARRATIVE_CHUNKING_STRATEGY_VERSION
    assert metadata["chunk_level"] == "child"
    assert metadata["chunker"] == "llamaindex_sentence_splitter"
    assert metadata["pageStart"] == 3


def test_select_chunking_strategy_uses_narrative_sentence_strategy_for_novel_profile() -> None:
    strategy = select_chunking_strategy(NOVEL_NARRATIVE_PROFILE)

    assert isinstance(strategy, LlamaSentenceChunkingStrategy)
    assert strategy.name == NARRATIVE_CHUNKING_STRATEGY
