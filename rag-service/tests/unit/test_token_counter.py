from app.ingestion.chunkers.base import Chunk
from app.ingestion.chunking import APPROX_TOKEN_COUNTER, ApproxTokenCounter, attach_token_counts


def test_approx_token_counter_returns_zero_for_empty_text() -> None:
    counter = ApproxTokenCounter()

    assert counter.count("") == 0
    assert counter.count("   ") == 0


def test_approx_token_counter_counts_words_and_punctuation() -> None:
    counter = ApproxTokenCounter()

    assert counter.count("Minh opened the old book, then smiled.") > 0


def test_attach_token_counts_adds_chunk_metadata() -> None:
    chunks = [Chunk(text="Minh opened the old book, then smiled.", metadata={})]

    attach_token_counts(chunks, ApproxTokenCounter())

    assert chunks[0].metadata["token_count"] > 0
    assert chunks[0].metadata["token_counter"] == APPROX_TOKEN_COUNTER
