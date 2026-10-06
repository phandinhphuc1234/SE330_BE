from app.ingestion.chunkers.section_chunker import SectionChunker


def test_section_chunker_returns_chunks(sample_text):
    chunks = SectionChunker().chunk(sample_text)
    assert chunks
