from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.indexing import LibraryVectorSearchScope, SearchResult
from app.retrieval.context_expander import ContextCompressor, ContextExpander, NeighborChunk
from app.retrieval.hybrid_retriever import fuse_search_results, reciprocal_rank_fusion
from app.retrieval.keyword_retriever import KeywordCandidate, KeywordRetriever, PostgresKeywordCandidateSource, bm25_score_candidates
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalHit, LibraryVectorRetrievalRequest, LibraryVectorRetrievalResponse
from app.retrieval.query_rewriter import QueryRewriter
from app.retrieval.reranker import Reranker
from app.retrieval.retrieval_pipeline import RetrievalPipeline


def settings(**kwargs):
    return Settings(_env_file=None, **kwargs)


def candidate(index, text):
    return KeywordCandidate(index, f"chunk-{index}", text, {"ebook_id": 10})


def result(key, score=.8, **metadata):
    return SearchResult(key, score, "Della sold her hair for twenty dollars.", vector_id=key,
                        metadata={"ebook_id": 10, "document_id": 1, "chunk_index": 2,
                                  "pageStart": 3, "embedding_version": "v1", **metadata})


def response(hits):
    return LibraryVectorRetrievalResponse("hash", "policy", "v1", 5, {"ebook_id": 10}, hits)


def hit(key="one", score=.8):
    value = result(key, score)
    return LibraryVectorRetrievalHit(key, score, value.text, key,
                                     {"ebookId": 10, "pageStart": 3}, value.metadata)


def test_bm25_prefers_rare_specific_terms_and_returns_no_unrelated_hits():
    candidates = [candidate(1, "Jim sold his watch."), candidate(2, "Mrs Sofronie bought Della's hair."),
                  candidate(3, "Della met Jim. Jim met Della.")]
    ranked = bm25_score_candidates(["sofronie", "hair"], candidates)
    assert [value.chunk_id for _, value in ranked] == [2]
    assert bm25_score_candidates(["database"], candidates) == []


@pytest.mark.asyncio
async def test_keyword_passes_full_scope_and_bounded_candidate_budget():
    source = SimpleNamespace(load=AsyncMock(return_value=[candidate(2, "Sofronie buys hair")]))
    scope = LibraryVectorSearchScope(book_id=100, ebook_id=10, document_id=1, embedding_version="v1")
    retriever = KeywordRetriever(candidate_source=source, settings=settings(keyword_candidate_limit=200))
    results = await retriever.search("sofronie", 3, scope=scope)
    source.load.assert_awaited_once_with(scope, limit=200, query="sofronie")
    assert results[0].retrieval_source == "keyword"
    assert results[0].metadata["bm25_score"] > 0


@pytest.mark.asyncio
async def test_keyword_repository_sql_requires_indexed_current_version_and_intersects_scope():
    session = SimpleNamespace(execute=AsyncMock(return_value=SimpleNamespace(all=lambda: [])))
    context = AsyncMock()
    context.__aenter__.return_value = session
    source = PostgresKeywordCandidateSource(session_factory=lambda: context)
    scope = LibraryVectorSearchScope(book_id=100, ebook_id=10, document_id=1, embedding_version="v1")
    await source.load(scope, limit=100, query="hair")
    from sqlalchemy.dialects import postgresql
    statement = session.execute.call_args.args[0]
    sql = str(statement.compile(dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}))
    for clause in ("documents.book_id = 100", "documents.ebook_id = 10", "documents.id = 1",
                   "INDEXED", "embedding_version", "LIBRARY_EBOOK"):
        assert clause in sql


@pytest.mark.asyncio
async def test_large_keyword_scope_prefilters_with_matching_gin_expression():
    session = SimpleNamespace(execute=AsyncMock(side_effect=[
        SimpleNamespace(all=lambda: [object(), object()]), SimpleNamespace(all=lambda: []),
    ]))
    context = AsyncMock()
    context.__aenter__.return_value = session
    source = PostgresKeywordCandidateSource(session_factory=lambda: context)
    await source.load(LibraryVectorSearchScope(ebook_id=10, embedding_version="v1"), limit=1, query="Della hair")
    from sqlalchemy.dialects import postgresql
    sql = str(session.execute.call_args.args[0].compile(dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}))
    assert "to_tsvector('simple'::regconfig, document_chunks.content)" in sql
    assert "@@ to_tsquery('simple'::regconfig, 'della | hair')" in sql
    assert "documents.ebook_id = 10" in sql
    assert "LIMIT 1" in sql


def test_rrf_deduplicates_each_ranking_and_never_inflates_repeated_id():
    assert reciprocal_rank_fusion([["a", "a", "b"]]) == reciprocal_rank_fusion([["a", "b"]])
    with pytest.raises(ValueError):
        reciprocal_rank_fusion([["a"]], k=0)


def test_fusion_ranks_by_rrf_but_keeps_cosine_for_answer_threshold():
    vector = [result("a", .9), result("b", .61)]
    lexical = [result("b", 1.0), result("c", .8)]
    fused = fuse_search_results(vector_results=vector, keyword_result_sets=[lexical],
                                vector_weight=.5, keyword_weight=.5)
    assert [item.vector_id for item in fused] == ["b", "a", "c"]
    assert fused[0].score == .61
    assert fused[0].metadata["keyword_score"] == 1
    assert fused[2].score == 0  # Lexical score is not semantic confidence.


@pytest.mark.asyncio
async def test_rewriter_normalizes_whitespace_and_preserves_original_entities():
    variants = await QueryRewriter().rewrite("  What did Della sell\n to buy Jim a gift? ")
    assert variants[0] == "What did Della sell to buy Jim a gift?"
    assert "della" in variants[1] and "jim" in variants[1]
    with pytest.raises(ValueError, match="blank"):
        await QueryRewriter().rewrite("  ")


@pytest.mark.asyncio
async def test_reranker_deduplicates_and_preserves_cosine_scores():
    results = [result("a", .8, rrf_score=.5), result("b", .7, rrf_score=1), result("b", .7)]
    ranked = await Reranker().rerank("Della sold hair", results, 5)
    assert [value.vector_id for value in ranked] == ["b", "a"]
    assert ranked[0].score == .7


@pytest.mark.asyncio
async def test_context_expansion_keeps_each_neighbors_id_and_page_and_rejects_other_ebook():
    chapter = {"chapter_index": 1, "chapter_title": "Chapter 1"}
    seed = result("seed", **chapter)
    source = SimpleNamespace(load=AsyncMock(return_value=[
        NeighborChunk(1, 1, "Earlier page.", "previous", {"ebook_id": 10, "pageStart": 2, "embedding_version": "v1", **chapter}),
        NeighborChunk(1, 2, seed.text, "seed", {"ebook_id": 10, "pageStart": 3, "embedding_version": "v1", **chapter}),
        NeighborChunk(1, 3, "Private content.", "other", {"ebook_id": 99, "pageStart": 4, "embedding_version": "v1", **chapter}),
    ]))
    expanded = await ContextExpander(source=source).expand([seed], window=1)
    assert [value.vector_id for value in expanded] == ["seed", "previous"]
    assert expanded[0].metadata["pageStart"] == 3
    assert expanded[1].metadata["pageStart"] == 2
    assert expanded[1].text == "Earlier page."


@pytest.mark.asyncio
async def test_compressor_respects_global_budget_and_keeps_relevant_sentence():
    items = [SearchResult("a", .8, "This is irrelevant. Della sold her hair for twenty dollars. Another sentence."),
             SearchResult("b", .8, "Other evidence.")]
    compressed = await ContextCompressor().compress("Della hair", items, max_context_chars=45)
    assert sum(len(item.context_text) for item in compressed) <= 45
    assert "Della sold her hair" in compressed[0].context_text


@pytest.mark.asyncio
async def test_dense_fallback_retains_original_scores_and_requested_count():
    dense = SimpleNamespace(search=AsyncMock(return_value=response([hit(score=.81), hit("weak", .4)])))
    keyword = SimpleNamespace(search=AsyncMock(side_effect=RuntimeError("database unavailable")))
    pipeline = RetrievalPipeline(vector_retriever=dense, keyword_retriever=keyword, settings=settings())
    data = await pipeline.search(LibraryVectorRetrievalRequest("Della hair", ebook_id=10, top_k=1, score_threshold=.6))
    assert data.top_k == 1
    assert data.results[0].score == .81
    assert data.results[0].metadata.get("rrf_score") is None


@pytest.mark.asyncio
async def test_pipeline_does_not_treat_keyword_only_result_as_strong_answer_evidence():
    dense = SimpleNamespace(search=AsyncMock(return_value=response([hit(score=.2)])))
    keyword = SimpleNamespace(search=AsyncMock(return_value=[result("keyword-only", 1.0)]))
    pipeline = RetrievalPipeline(vector_retriever=dense, keyword_retriever=keyword, settings=settings())
    data = await pipeline.search(LibraryVectorRetrievalRequest("Della hair", ebook_id=10, top_k=5, score_threshold=.6))
    assert data.results == []


@pytest.mark.asyncio
async def test_dense_override_skips_keyword_branch():
    dense = SimpleNamespace(search=AsyncMock(return_value=response([hit()])))
    keyword = SimpleNamespace(search=AsyncMock())
    pipeline = RetrievalPipeline(vector_retriever=dense, keyword_retriever=keyword, settings=settings())
    data = await pipeline.search(LibraryVectorRetrievalRequest("Della hair", ebook_id=10, retrieval_mode="dense"))
    assert data.results[0].score == .8
    keyword.search.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("fail_open", [True, False])
async def test_optional_context_failure_preserves_dense_seed_or_fails_closed(fail_open):
    dense = SimpleNamespace(search=AsyncMock(return_value=response([hit()])))
    expander = SimpleNamespace(expand=AsyncMock(side_effect=RuntimeError("unavailable")))
    pipeline = RetrievalPipeline(vector_retriever=dense, context_expander=expander,
                                 settings=settings(hybrid_fail_open=fail_open))
    request = LibraryVectorRetrievalRequest("Della hair", ebook_id=10, retrieval_mode="dense", expand_context=True)
    if not fail_open:
        with pytest.raises(RuntimeError):
            await pipeline.search(request)
    else:
        data = await pipeline.search(request)
        assert data.results[0].vector_id == "one"
        assert data.results[0].score == .8
        assert data.results[0].context_text == data.results[0].text


@pytest.mark.asyncio
@pytest.mark.parametrize("top_k", [0, -1, 51, True])
async def test_pipeline_rejects_invalid_top_k(top_k):
    pipeline = RetrievalPipeline(vector_retriever=SimpleNamespace(search=AsyncMock()), settings=settings())
    with pytest.raises(ValueError):
        await pipeline.search(LibraryVectorRetrievalRequest("question", ebook_id=10, top_k=top_k))
