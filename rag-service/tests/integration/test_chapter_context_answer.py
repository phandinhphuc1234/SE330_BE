"""Synthetic PDF-page -> compact vector payload -> expanded answer HTTP flow.

Real cleaner/chunker/retrieval/prompt/citation logic, deterministic I/O adapters.
This is not a live PDF, PostgreSQL, Qdrant, embedding or LLM quality benchmark.
"""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest

from app.api.internal.routes_answers import get_answer_generator
from app.core.config import Settings, get_settings
from app.generation.answer_generator import AnswerGenerator
from app.indexing import SearchResult, VectorChunk
from app.indexing.qdrant_store import QdrantVectorStore
from app.ingestion.parsers.base import ParsedDocument, ParserRegistry
from app.ingestion.pipeline import IngestionPipeline
from app.main import create_app
from app.retrieval.context_expander import ContextExpander, PostgresNeighborChunkSource
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalService
from app.retrieval.retrieval_pipeline import RetrievalPipeline


@pytest.mark.asyncio
async def test_v2_answer_cites_only_same_chapter_neighbor_from_real_chunk_provenance(monkeypatch):
    settings = Settings.model_construct(
        embedding_dim=3, embedding_version="test-embedding-v1", retrieval_mode="dense",
        chunking_strategy_version="v2", chunk_size=128, chunk_overlap=16,
        rag_internal_api_key="test-only-key", context_expansion_window=1,
    )
    for target in ("app.ingestion.pipeline.get_settings", "app.ingestion.cleaners.pdf_cleaner.get_settings",
                   "app.ingestion.llama_index.node_adapter.get_settings", "app.core.internal_auth.get_settings"):
        monkeypatch.setattr(target, lambda: settings)
    monkeypatch.setattr("app.ingestion.pipeline.IngestionArtifactWriter", lambda: None)
    document = SimpleNamespace(
        id=7, external_document_id="doc_ebook_55", source_type="LIBRARY_EBOOK", source_id="ebook:55",
        book_id=101, ebook_id=55, filename="synthetic.pdf", metadata_={
            "ingestion_status": "INDEXED", "embedding_version": settings.embedding_version,
            "source_checksum_sha256": "a" * 64, "chunking_strategy_version": "v2",
        },
    )
    passages = [f"Minh dừng ở kệ sách số {index} và tìm thấy một lá thư của người thủ thư. "
                "Cậu đọc kỹ lời nhắn rồi quay lại căn phòng có chiếc chìa khóa bạc."
                for index in range(12)]
    parsed = [
        ParsedDocument("Chương 1\n\n" + "\n\n".join(passages[:6]), {"page_number": 1}),
        ParsedDocument("\n\n".join(passages[6:]) + "\n\nChương 2\n\n"
                       "Lan đang ở một bến tàu khác. Cô tìm chiếc la bàn đỏ để bắt đầu chuyến đi mới.",
                       {"page_number": 2}),
    ]
    chunks = IngestionPipeline(parser_registry=ParserRegistry([]))._build_chunk_result(
        parsed, document=document, source_checksum_sha256="a" * 64,
    ).chunks
    for chunk in chunks:
        chunk.metadata["embedding_version"] = settings.embedding_version
    first_new_chapter = next(i for i, chunk in enumerate(chunks) if chunk.metadata["chapter_index"] == 2)
    assert first_new_chapter >= 2
    seed_chunk, previous, forbidden = chunks[first_new_chapter - 1], chunks[first_new_chapter - 2], chunks[first_new_chapter]
    seed_id, previous_id, forbidden_id = [c.metadata["vector_id"] for c in (seed_chunk, previous, forbidden)]

    # Use the actual allowlist: this catches missing section/checksum fields
    # that dictionary-only expander tests would otherwise overlook.
    adapter = QdrantVectorStore(settings=settings, client=SimpleNamespace())
    payload = adapter._build_payload(
        VectorChunk(seed_id, seed_chunk.text, [.1, .2, .3], seed_chunk.metadata),
        point_id="seed-point", vector_key=f"{seed_id}:{settings.embedding_version}",
    )
    store = SimpleNamespace(search=AsyncMock(return_value=[SearchResult(
        "seed-point", .9, seed_chunk.text, payload, vector_id=seed_id,
    )]))
    dense = LibraryVectorRetrievalService(
        settings=settings, vector_store=store,
        embedding_provider=SimpleNamespace(embed_query=AsyncMock(return_value=[.1, .2, .3])),
    )
    db_rows = [(SimpleNamespace(document_id=7, chunk_index=c.metadata["chunk_index"],
                               content=c.text, vector_id=c.metadata["vector_id"], metadata_=c.metadata), document)
               for c in chunks]
    session = SimpleNamespace(execute=AsyncMock(return_value=SimpleNamespace(all=lambda: db_rows)))
    context = AsyncMock()
    context.__aenter__.return_value = session
    source = PostgresNeighborChunkSource(session_factory=lambda: context, settings=settings)
    pipeline = RetrievalPipeline(vector_retriever=dense, context_expander=ContextExpander(source=source),
                                 settings=settings)
    llm = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        "answer": "Minh đọc lời nhắn của người thủ thư.", "abstained": False, "citationIds": [previous_id],
    })))
    generator = AnswerGenerator(retrieval_service=pipeline, llm_client=llm, settings=settings)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_answer_generator] = lambda: generator
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/internal/answers", headers={"X-RAG-API-Key": "test-only-key"},
                                     json={"question": "Minh đọc lời nhắn của ai?", "ebookId": 55})
    assert response.status_code == 200
    data = response.json()
    assert data["grounded"] is True and data["abstained"] is False
    citation = data["citations"][0]
    assert citation["chunkId"] == previous_id and citation["chapterTitle"] == "Chương 1"
    assert citation["pageStart"] == previous.metadata["pageStart"]
    assert citation["pageEnd"] == previous.metadata["pageEnd"]
    assert citation["excerpt"] == previous.text[:600]
    prompt = llm.complete_json.call_args.kwargs["user_prompt"]
    assert f'<source id="{seed_id}">' in prompt and f'<source id="{previous_id}">' in prompt
    assert f'<source id="{forbidden_id}">' not in prompt and "la bàn đỏ" not in prompt
    assert store.search.call_args.args[0].filters["ebook_id"] == 55
