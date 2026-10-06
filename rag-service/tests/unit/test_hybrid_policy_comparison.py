"""Fixed experiment, default compatibility, fail-closed references and review gates."""

from copy import deepcopy
from dataclasses import FrozenInstanceError
import hashlib
import json

import pytest

from app.core.config import Settings
from app.evaluation.chunking_comparison import load_dataset
from app.evaluation.hybrid_policy_comparison import (
    POLICIES, HybridPolicy, experiment_hash, experiment_manifest, review_policies,
)
from app.evaluation.retrieval_diagnostics import explain_hybrid_trace
from app.evaluation.chunking_comparison import AnchoredEvidence
from app.indexing import SearchResult
from app.retrieval.ranking_trace import RankingTrace
from app.retrieval.reranker import Reranker
from scripts.benchmark_book_chunking import DEFAULT_DATASET
from scripts.compare_hybrid_policies import build_comparison, main, read_reference, without_diagnostics
from scripts.benchmark_chunking import BASELINE_CONFIG
from app.ingestion.parsers.base import ParsedDocument


def result(key, score, rrf, text="needle"):
    return SearchResult(key, score, text, {"rrf_score": rrf}, vector_id=key)


@pytest.mark.parametrize("weights", [(-.1, .9, .2), (True, 0, 0), (float("nan"), 0, 1),
                                      (float("inf"), 0, 1), (.4, .4, .1), (".4", .5, .1)])
def test_reranker_rejects_invalid_weights_before_scoring(weights):
    with pytest.raises(ValueError):
        Reranker(rank_weight=weights[0], semantic_weight=weights[1], lexical_weight=weights[2])


@pytest.mark.parametrize("weights", [(-.1, 1.1), (True, 0), (.8, .3), (float("nan"), .3)])
def test_policy_rejects_invalid_fusion_weights(weights):
    with pytest.raises(ValueError):
        HybridPolicy("test", *weights, .4, .5, .1)


def test_experiment_weights_and_review_rules_frozen_before_first_run():
    assert experiment_hash() == "c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9"
    assert len(POLICIES) == 5
    assert experiment_manifest()["reviewRules"]["automaticPromotion"] is False
    with pytest.raises(FrozenInstanceError):
        POLICIES[1].vector_weight = .99


def test_manifest_export_cannot_mutate_registered_review_rules():
    exported = experiment_manifest()
    exported["reviewRules"]["noPerCaseRecall3Loss"] = False
    exported["policies"][1]["vector_weight"] = .99
    assert experiment_hash() == "c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9"


@pytest.mark.asyncio
async def test_default_reranker_reproduces_old_formula_dedup_order_metadata_and_public_scores():
    items = [result("a", .9, .7), result("b", .7, 1), result("a", .1, 1)]
    original = deepcopy(items)
    ranked = await Reranker().rerank("needle", items, 5)
    expected = sorted(items[:2], key=lambda row: -(.4 * row.metadata["rrf_score"] + .5 * row.score + .1))
    assert [row.vector_id for row in ranked] == [row.vector_id for row in expected]
    for actual, old in zip(ranked, expected, strict=True):
        assert actual.score == old.score
        assert actual.metadata == old.metadata | {"lexical_coverage": 1., "pre_rerank_score": old.score,
                                                   "reranker_score": .4 * old.metadata["rrf_score"] + .5 * old.score + .1}
    assert items == original
    reranker = Reranker()
    weights = reranker.weights
    weights["rrf"] = 42
    assert reranker.weights == {"rrf": .4, "cosine": .5, "lexical": .1}


@pytest.mark.asyncio
@pytest.mark.parametrize("policy", POLICIES, ids=lambda policy: policy.id)
async def test_policies_preserve_cosine_nonmutation_and_keyword_only_confidence(policy):
    settings = Settings.model_construct(gemini_api_key="DO_NOT_EXPORT")
    original = settings.model_dump()
    updated = policy.apply(settings)
    assert settings.model_dump() == original
    assert updated.hybrid_vector_weight == policy.vector_weight
    assert updated.context_expansion_window == settings.context_expansion_window
    items = [result("semantic", .9, .7), result("keyword-only", 0., 1)]
    before = deepcopy(items)
    ranked = await policy.reranker().rerank("needle", items, 2)
    assert {row.vector_id: row.score for row in ranked}["keyword-only"] == 0.
    assert items == before


@pytest.mark.asyncio
async def test_semantic_reranker_injection_changes_order_without_changing_score():
    items = [result("semantic", .9, .7), result("agreement", .7, 1)]
    assert (await Reranker().rerank("needle", items, 2))[0].vector_id == "agreement"
    ranked = await POLICIES[2].reranker().rerank("needle", items, 2)
    assert ranked[0].vector_id == "semantic" and ranked[0].score == .9


@pytest.mark.asyncio
async def test_diagnostics_explain_injected_weights_not_hardcoded_baseline():
    trace = RankingTrace()
    reranker = POLICIES[2].reranker()
    trace.begin(rerankerWeights=reranker.weights)
    ranked = await reranker.rerank("needle", [result("a", .8, .6)], 1)
    trace.record("reranked_candidates", ranked)
    report = explain_hybrid_trace(trace, [AnchoredEvidence(1, "needle", frozenset(range(6)))], {"a": [(1, 0, 6)]})
    row = report["stages"]["reranked_candidates"][0]
    assert row["rerankerContributions"] == pytest.approx({"rrf": .09, "cosine": .64, "lexical": .05})


def evaluation(recalls=(1., 1.), mrr=(1., 1.), contexts=(1., 1.)):
    cases = [{"id": f"q{index}", "metricsAtK": {"3": {"evidenceRecall": recall}, "5": {"reciprocalRank": reciprocal}},
              "contextRetainedAnchorRate": context}
             for index, (recall, reciprocal, context) in enumerate(zip(recalls, mrr, contexts, strict=True))]
    return {"cases": cases, "metricsAtK": {"3": {"evidenceRecall": sum(recalls) / len(recalls)},
                                          "5": {"reciprocalRank": sum(mrr) / len(mrr)}},
            "contextRetainedAnchorRate": sum(contexts) / len(contexts)}


def segment(**base_updates):
    base = evaluation(**base_updates)
    return {"datasetId": "en", "bookId": "book", "chunker": "v1",
            "policies": {policy.id: deepcopy(base) for policy in POLICIES}}


def review(decision, policy_id):
    return next(row for row in decision["reviews"] if row["policyId"] == policy_id)


def test_case_recall_loss_cannot_be_hidden_by_equal_aggregate_gain():
    group = segment(recalls=(1., 0.))
    group["policies"][POLICIES[1].id] = evaluation(recalls=(0., 1.))
    decision = review_policies([group])
    measured = review(decision, POLICIES[1].id)
    assert measured["macro"]["recall3"] == .5 and not measured["eligibleForFreshValidation"]
    assert any(loss["kind"] == "caseRecall3" for loss in measured["regressions"])


@pytest.mark.parametrize("change", ["mrr", "contexts"])
def test_segment_mrr_and_context_regressions_reject_candidate_even_when_recall_equal(change):
    group = segment()
    group["policies"][POLICIES[1].id] = evaluation(**{change: (.5, 1.)})
    measured = review(review_policies([group]), POLICIES[1].id)
    assert not measured["eligibleForFreshValidation"]
    assert any(loss["kind"] == "segment" for loss in measured["regressions"])


def test_mrr_case_tradeoff_is_reported_even_if_aggregate_improves():
    group = segment(mrr=(1., .2))
    group["policies"][POLICIES[1].id] = evaluation(mrr=(.8, 1.))
    measured = review(review_policies([group]), POLICIES[1].id)
    assert measured["eligibleForFreshValidation"]
    assert any(change["delta"]["mrr5"] < 0 for change in measured["caseChanges"])


def test_nomination_is_not_runtime_promotion_and_ties_keep_registered_order():
    decision = review_policies([segment()])
    assert decision["nomineeForFreshValidation"] == POLICIES[1].id
    assert decision["runtimePolicy"] == "baseline_v1" and not decision["automaticPromotion"]


def test_no_candidate_passes_when_any_segment_has_regressions():
    first, second = segment(), segment()
    second["bookId"] = "other"
    for policy in POLICIES[1:]:
        second["policies"][policy.id] = evaluation(recalls=(0., 0.))
    decision = review_policies([first, second])
    assert decision["nomineeForFreshValidation"] is None
    assert "HOLD" in decision["recommendation"]


@pytest.mark.parametrize("change", ["empty", "missing", "duplicate"])
def test_review_rejects_empty_or_unfair_case_sets(change):
    group = segment()
    if change == "empty":
        with pytest.raises(ValueError):
            review_policies([])
        return
    cases = group["policies"][POLICIES[1].id]["cases"]
    if change == "missing":
        cases.pop()
    else:
        cases.append(deepcopy(cases[0]))
    with pytest.raises(ValueError, match="exactly match"):
        review_policies([group])


def reference_payload(dataset, settings):
    return {"errorCount": 0, "dataset": {"manifestContentSha256": hashlib.sha256(json.dumps(
        dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()},
        "embedding": {"status": "passed", "model": settings.embedding_model, "dimension": settings.embedding_dim,
                      "version": settings.embedding_version, "textPolicy": settings.embedding_text_policy},
        "config": {"chunkSize": 512, "chunkOverlap": 64, "topK": [1, 3, 5], "contextSeedK": 3,
                   "contextWindow": 1, "contextBudgetCharacters": 12000, "sameParserAndCleanedText": True},
        "books": [{"id": book.id, "sha256": book.sha256} for book in dataset.books]}


@pytest.mark.parametrize("drift", ["dataset", "embedding", "source", "context", "failed"])
def test_reference_drift_is_rejected_before_experiment(tmp_path, drift):
    dataset, settings = load_dataset(DEFAULT_DATASET), Settings.model_construct()
    payload = reference_payload(dataset, settings)
    if drift == "dataset":
        payload["dataset"]["manifestContentSha256"] = "0" * 64
    elif drift == "embedding":
        payload["embedding"]["dimension"] += 1
    elif drift == "source":
        payload["books"][0]["sha256"] = "0" * 64
    elif drift == "context":
        payload["config"]["contextBudgetCharacters"] = 10
    else:
        payload["errorCount"] = 1
    path = tmp_path / "reference.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError):
        read_reference(path, dataset, settings)


def test_strip_diagnostics_does_not_mutate_case_metrics_or_input():
    measured = {"hybrid": evaluation()}
    measured["hybrid"]["cases"][0]["rankingDiagnostics"] = {"count": 42}
    original = deepcopy(measured)
    assert "rankingDiagnostics" not in without_diagnostics(measured)["hybrid"]["cases"][0]
    assert measured == original


@pytest.mark.asyncio
async def test_holdout_checksum_guard_precedes_references_pdf_cache_or_settings(tmp_path, monkeypatch):
    english = load_dataset(DEFAULT_DATASET)
    vi = load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json"))
    vi.baseline_dataset_sha256 = "0" * 64
    monkeypatch.setattr("scripts.compare_hybrid_policies.trace_settings", lambda: pytest.fail("No config read"))
    with pytest.raises(ValueError, match="checksum"):
        await build_comparison(english, vi, tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")


@pytest.mark.parametrize("suffix", [".json", ".md"])
def test_cli_never_overwrites_either_report_before_data_work(tmp_path, monkeypatch, suffix):
    output = tmp_path / "report.json"
    output.with_suffix(suffix).write_text("user-owned", encoding="utf-8")
    monkeypatch.setattr("scripts.compare_hybrid_policies.load_dataset", lambda *_a: pytest.fail("No data read"))
    assert main(["--output", str(output)]) == 1
    assert output.with_suffix(suffix).read_text() == "user-owned"


@pytest.mark.asyncio
@pytest.mark.parametrize("missing", ["document", "query"])
async def test_comparison_missing_cache_never_calls_provider_or_embed_fallback(tmp_path, monkeypatch, missing):
    english = load_dataset(DEFAULT_DATASET)
    vi = load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json"))
    monkeypatch.setattr("scripts.compare_hybrid_policies.trace_settings", lambda: Settings.model_construct(
        **BASELINE_CONFIG, embedding_dim=2, context_expansion_window=1, gemini_api_key="DO_NOT_EXPORT"))
    monkeypatch.setattr("scripts.compare_hybrid_policies.read_reference", lambda *_a: {})
    monkeypatch.setattr("scripts.compare_hybrid_policies.parse_pdf", lambda *_a, **_k: [
        ParsedDocument("A short source paragraph for the cache guard test.", {"page_number": 1})])
    monkeypatch.setattr("scripts.compare_hybrid_policies.CachedGemini.read", lambda _self, task, text:
                        None if task == missing else [1., 0.])
    async def forbidden(*_a, **_k):
        pytest.fail("No embedding/provider fallback is allowed")
    monkeypatch.setattr("scripts.compare_hybrid_policies.CachedGemini.embed_documents", forbidden)
    monkeypatch.setattr("scripts.compare_hybrid_policies.CachedGemini.embed_query", forbidden)
    with pytest.raises(ValueError, match=f"{missing} cache missing"):
        await build_comparison(english, vi, tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")
