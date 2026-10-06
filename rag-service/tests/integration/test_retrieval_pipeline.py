"""Exercise the HTTP boundary with real orchestration and deterministic adapters."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest

from app.api.internal.routes_answers import get_answer_generator
from app.api.internal.routes_retrieval import get_library_vector_retrieval_service
from app.core.config import Settings, get_settings
from app.generation.answer_generator import AnswerGenerator
from app.indexing import SearchResult
from app.main import create_app
from app.retrieval.keyword_retriever import KeywordCandidate, KeywordRetriever
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalService
from app.retrieval.retrieval_pipeline import RetrievalPipeline


@pytest.mark.asyncio
async def test_http_hybrid_to_grounded_answer_and_abstention_with_scope_filters(monkeypatch):
    settings = Settings(_env_file=None, embedding_dim=3, embedding_version="test-v1",
                        rag_internal_api_key="test-only-key", context_expansion_window=0)
    monkeypatch.setattr("app.core.internal_auth.get_settings", lambda: settings)
    metadata = {"ebook_id": 10, "book_id": 100, "document_id": 1, "pageStart": 3,
                "chunk_index": 0, "embedding_version": "test-v1", "vector_id": "hair"}
    store = SimpleNamespace(search=AsyncMock(return_value=[SearchResult(
        "point-hair", .9, "Della sold her hair.", metadata, vector_id="hair",
    )]))
    embedder = SimpleNamespace(embed_query=AsyncMock(return_value=[.1, .2, .3]))
    dense = LibraryVectorRetrievalService(settings=settings, vector_store=store, embedding_provider=embedder)
    source = SimpleNamespace(load=AsyncMock(return_value=[KeywordCandidate(1, "hair", "Della sold her hair.", metadata)]))
    lexical = KeywordRetriever(candidate_source=source, settings=settings)
    pipeline = RetrievalPipeline(vector_retriever=dense, keyword_retriever=lexical, settings=settings)
    llm = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        "answer": "Della sold her hair.", "abstained": False, "citationIds": ["hair"],
    })))
    generator = AnswerGenerator(retrieval_service=pipeline, llm_client=llm, settings=settings)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_library_vector_retrieval_service] = lambda: pipeline
    app.dependency_overrides[get_answer_generator] = lambda: generator
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        denied = await client.post("/internal/retrieval/search", json={"query": "Della", "ebookId": 10})
        assert denied.status_code == 401
        headers = {"X-RAG-API-Key": "test-only-key"}
        search = await client.post("/internal/retrieval/search", headers=headers,
                                   json={"query": "Della hair", "ebookId": 10, "topK": 3})
        assert search.status_code == 200
        assert search.json()["results"][0]["metadata"]["retrieval_source"] == "hybrid"
        answer = await client.post("/internal/answers", headers=headers,
                                   json={"question": "What did Della sell?", "ebookId": 10})
        assert answer.status_code == 200
        assert answer.json()["citations"][0]["chunkId"] == "hair"
        assert store.search.call_args.args[0].filters["ebook_id"] == 10
        store.search.return_value = []
        source.load.return_value = []
        no_evidence = await client.post("/internal/answers", headers=headers,
                                        json={"question": "Unknown fact?", "ebookId": 10})
        assert no_evidence.json()["abstained"] is True
