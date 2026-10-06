"""Opt-in tracing must explain ranking without changing scores or HTTP output."""

from copy import deepcopy
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.evaluation.chunking_comparison import AnchoredEvidence, load_dataset
from app.evaluation.retrieval_diagnostics import explain_hybrid_trace
from app.indexing import SearchResult
from app.ingestion.parsers.base import ParsedDocument
from app.retrieval.hybrid_retriever import fuse_search_results
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalHit, LibraryVectorRetrievalRequest, LibraryVectorRetrievalResponse,
)
from app.retrieval.ranking_trace import RankingTrace
from app.retrieval.retrieval_pipeline import RetrievalPipeline
from scripts.benchmark_book_chunking import DEFAULT_DATASET
from scripts.trace_hybrid_retrieval import CacheOnlyQueryProvider, build_trace_report, main


def result(key, score=.8, **metadata):
    return SearchResult(key, score, "DO_NOT_EXPORT source text", vector_id=key,
                        metadata={"pageStart": 1, "pageEnd": 1, "ebook_id": 10,
                                  "document_id": 1, "chunk_index": 0, **metadata})


def dense_response():
    hits = [LibraryVectorRetrievalHit(key, score, "DO_NOT_EXPORT source text", key,
                                     {"pageStart": 1}, result(key).metadata)
            for key, score in (("a", .9), ("b", .7), ("c", .3))]
    return LibraryVectorRetrievalResponse("hash", "policy", "v1", 3, {"ebook_id": 10}, hits)


def pipeline(*, keyword_error=False, **updates):
    keyword = SimpleNamespace(search=AsyncMock(
        side_effect=RuntimeError("DO_NOT_EXPORT DB error") if keyword_error else None,
        return_value=[result("b", 1), result("d", .6)],
    ))
    return RetrievalPipeline(settings=Settings(_env_file=None, **updates), keyword_retriever=keyword,
                             vector_retriever=SimpleNamespace(search=AsyncMock(return_value=dense_response())),
                             graph_retriever=SimpleNamespace(search=AsyncMock(return_value=[result("a", .6)])))


def test_trace_copies_scores_and_whitelists_metadata_without_text_or_secret():
    trace = RankingTrace()
    hit = result("a", secret="DO_NOT_EXPORT key", query="DO_NOT_EXPORT query", rrf_score=.7)
    trace.record("stage", [hit])
    before = trace.export()
    hit.score = .1
    hit.metadata["rrf_score"] = .1
    assert trace.export() == before
    before["stages"]["stage"][0]["score"] = 42
    assert trace.export()["stages"]["stage"][0]["score"] == .8
    assert "DO_NOT_EXPORT" not in json.dumps(trace.export())


def test_trace_begin_resets_previous_request_and_components():
    trace = RankingTrace()
    trace.record("old", [result("old")])
    trace.add_rrf("old", branch="vector", rank=1, weight=.7, contribution=.01)
    trace.outcome = "hybrid"
    trace.begin(mode="dense", topK=3)
    assert trace.stages == {} and trace.rrf_contributions == {}
    assert trace.outcome == "pending"


@pytest.mark.parametrize("weights", [(.7, .3), (1, 0), (0, 1)])
def test_rrf_trace_invariant_and_components_include_rewrite_weight_split(weights):
    vector = [result("a", .9), result("b", .7)]
    keywords = [[result("b", 1), result("b", 1), result("d", .5)], []]
    kwargs = dict(vector_results=vector, keyword_result_sets=keywords,
                  vector_weight=weights[0], keyword_weight=weights[1], limit=1)
    original = deepcopy((vector, keywords))
    expected = fuse_search_results(**kwargs)
    trace = RankingTrace()
    actual = fuse_search_results(**kwargs, trace=trace)
    assert actual == expected and (vector, keywords) == original
    assert len(trace.stages["rrf_all"]) >= len(actual)
    for row in trace.stages["rrf_all"]:
        contributions = trace.rrf_contributions[row["vectorId"]]
        assert sum(item["contribution"] for item in contributions) == pytest.approx(row["rrfRawScore"])
        for item in contributions:
            assert item["weight"] == (weights[0] if item["branch"] == "vector" else weights[1] / 2)
    assert sum(item["branch"] == "bm25_0" for item in trace.rrf_contributions.get("b", [])) <= 1


def test_empty_rrf_records_empty_union():
    trace = RankingTrace()
    assert fuse_search_results(vector_results=[], keyword_result_sets=[], vector_weight=.7,
                               keyword_weight=.3, trace=trace) == []
    assert trace.stages["rrf_all"] == []


def test_graph_rrf_component_is_observed_without_changing_output():
    trace = RankingTrace()
    kwargs = dict(vector_results=[result("a")], keyword_result_sets=[[]], vector_weight=.7,
                  keyword_weight=.3, graph_results=[result("a"), result("g")], graph_weight=.15)
    assert fuse_search_results(**kwargs, trace=trace) == fuse_search_results(**kwargs)
    assert trace.rrf_contributions["g"][0]["branch"] == "graph"


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["dense", "hybrid", "graph"])
@pytest.mark.parametrize("threshold", [None, .6])
async def test_pipeline_trace_preserves_response_and_captures_final_threshold(mode, threshold):
    service = pipeline()
    request = LibraryVectorRetrievalRequest("DO_NOT_EXPORT query", ebook_id=10, top_k=3,
                                           retrieval_mode=mode, score_threshold=threshold)
    expected = await service.search(request)
    trace = RankingTrace()
    actual = await service.search(request, trace=trace)
    assert actual == expected
    assert [row["vectorId"] for row in trace.stages["final_top_k"]] == [hit.vector_id for hit in actual.results]
    assert trace.outcome == mode
    assert "DO_NOT_EXPORT" not in json.dumps(trace.export())
    if mode != "dense":
        assert trace.parameters["candidateK"] == 9
        assert list(trace.stages).index("rrf_all") < list(trace.stages).index("rrf_candidates")
        assert len(trace.stages["threshold_passed"]) <= len(trace.stages["reranked_candidates"])


@pytest.mark.asyncio
async def test_lexical_outage_trace_preserves_actual_dense_fallback():
    service = pipeline(keyword_error=True)
    request = LibraryVectorRetrievalRequest("question", ebook_id=10, top_k=1, score_threshold=.6)
    trace = RankingTrace()
    assert await service.search(request, trace=trace) == await service.search(request)
    assert trace.outcome == "dense_fallback"
    assert set(trace.stages) == {"dense_candidates", "final_top_k"}
    assert trace.rrf_contributions == {}


@pytest.mark.asyncio
async def test_trace_does_not_change_fail_closed_behavior():
    with pytest.raises(RuntimeError):
        await pipeline(keyword_error=True, hybrid_fail_open=False).search(
            LibraryVectorRetrievalRequest("question", ebook_id=10), trace=RankingTrace())


def annotated(trace, page=1):
    keys = {row["vectorId"] for rows in trace.stages.values() for row in rows}
    ranges = {key: [(1 if key == "a" else 2, 0, 6)] for key in keys}
    return explain_hybrid_trace(trace, [AnchoredEvidence(page, "needle", frozenset(range(6)))], ranges)


def test_evidence_labels_cannot_change_production_ranking_or_trace():
    trace = RankingTrace()
    fused = fuse_search_results(vector_results=[result("a"), result("b")], keyword_result_sets=[[]],
                                vector_weight=.7, keyword_weight=.3, trace=trace)
    trace.record("final_top_k", fused)
    original = trace.export()
    first, second = annotated(trace), annotated(trace, page=2)
    assert trace.export() == original
    assert first["stageSummary"]["final_top_k"]["firstRelevantRank"] == 1
    assert second["stageSummary"]["final_top_k"]["firstRelevantRank"] == 2
    assert [(row["vectorId"], row["score"]) for row in first["stages"]["final_top_k"]] == [
        (row["vectorId"], row["score"]) for row in second["stages"]["final_top_k"]]


@pytest.mark.parametrize("loss_stage", ["rrf_candidates", "reranked_candidates", "threshold_passed"])
def test_loss_stage_distinguishes_candidate_eviction_reranking_and_threshold(loss_stage):
    trace = RankingTrace()
    good = [result("a"), result("b"), result("c"), result("d")]
    bad = [result("b"), result("c"), result("d"), result("a")]
    trace.record("dense_candidates", good)
    # For this synthetic test, skip rrf_all because no fusion occurred.
    changed = False
    for stage in ("rrf_candidates", "reranked_candidates", "threshold_passed", "final_top_k"):
        changed |= stage == loss_stage
        trace.record(stage, bad if changed else good)
    report = annotated(trace)
    assert report["firstTop3LossStage"] == loss_stage
    assert report["stageSummary"][loss_stage]["metricsAtAll"]["evidenceRecall"] == 1


def test_reranker_diagnostic_detects_formula_drift_instead_of_silent_wrong_explanation():
    trace = RankingTrace()
    trace.record("reranked_candidates", [result("a", rrf_score=.8, pre_rerank_score=.8,
                                                lexical_coverage=.2, reranker_score=42)])
    with pytest.raises(ValueError, match="weights drifted"):
        annotated(trace)


@pytest.mark.asyncio
@pytest.mark.parametrize("cached", [None, [.1, .2]])
async def test_query_provider_can_only_read_cache(cached):
    cache = SimpleNamespace(read=lambda *_args: cached)
    provider = CacheOnlyQueryProvider(cache)
    if cached is None:
        with pytest.raises(ValueError, match="cache missing"):
            await provider.embed_query("text")
    else:
        assert await provider.embed_query("text") == cached


def test_cli_rejects_existing_report_without_loading_or_overwriting(tmp_path, monkeypatch):
    output = tmp_path / "owned.json"
    output.write_text("user-owned", encoding="utf-8")
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.load_dataset", lambda *_args: pytest.fail("No work allowed"))
    assert main(["--output", str(output)]) == 1
    assert output.read_text() == "user-owned"


@pytest.mark.asyncio
async def test_unknown_case_rejected_before_cache_pdf_or_runtime_config(tmp_path, monkeypatch):
    dataset = load_dataset(DEFAULT_DATASET)
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.trace_settings", lambda: pytest.fail("No config read"))
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.parse_pdf", lambda *_a, **_k: pytest.fail("No PDF read"))
    with pytest.raises(ValueError, match="case IDs"):
        await build_trace_report(dataset, tmp_path, case_ids=("missing",))


@pytest.mark.asyncio
async def test_holdout_guard_runs_before_pdf_or_cached_embedding_work(tmp_path, monkeypatch):
    dataset = load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json"))
    dataset.baseline_dataset_sha256 = "0" * 64
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.parse_pdf", lambda *_a, **_k: pytest.fail("No PDF read"))
    with pytest.raises(ValueError, match="checksum"):
        await build_trace_report(dataset, tmp_path)


@pytest.mark.asyncio
async def test_missing_document_cache_fails_closed_without_provider_fallback(tmp_path, monkeypatch):
    dataset = load_dataset(DEFAULT_DATASET)
    # Real clean/chunk path, tiny synthetic source only. No fixture relabeling.
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.parse_pdf", lambda *_a, **_k: [
        ParsedDocument("A short synthetic paragraph with no external I/O.", {"page_number": 1})])
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.anchor_questions", lambda *_a: {})
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.trace_settings", lambda: Settings.model_construct(
        embedding_dim=2, gemini_api_key="DO_NOT_EXPORT"))
    async def forbidden(*_a, **_k):
        pytest.fail("Cache-only tracing must not use embed/provider methods")
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.CachedGemini.embed_documents", forbidden)
    monkeypatch.setattr("scripts.trace_hybrid_retrieval.CachedGemini.embed_query", forbidden)
    with pytest.raises(ValueError, match="document embedding cache missing"):
        await build_trace_report(dataset, tmp_path, case_ids=("birthplace",))


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["dense", "hybrid"])
async def test_answer_context_still_matches_with_trace_enabled(mode):
    service = pipeline()
    service.context_expander = SimpleNamespace(expand=AsyncMock(side_effect=lambda results, **_k: results))
    request = LibraryVectorRetrievalRequest("question", ebook_id=10, top_k=3,
                                           retrieval_mode=mode, expand_context=True, max_context_chars=100)
    assert await service.search(request, trace=RankingTrace()) == await service.search(request)
