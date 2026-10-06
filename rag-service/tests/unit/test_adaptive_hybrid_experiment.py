"""Sealed label-free offline hypotheses; baseline/defaults must not drift."""

from copy import deepcopy
from dataclasses import FrozenInstanceError
import json
import math
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.evaluation.adaptive_hybrid_experiment import (
    ADAPTIVE_POLICIES, AdaptiveDecision, AdaptivePolicy, experiment_hash_v2, experiment_manifest_v2,
    review_policies_v2, verify_registration,
)
from app.evaluation.chunking_comparison import (
    BookLabel, EvidenceLabel, QuestionLabel, build_chunk_result, evaluate_version, load_dataset,
)
from app.evaluation.hybrid_policy_comparison import experiment_hash
from app.indexing import SearchResult
from app.ingestion.parsers.base import ParsedDocument
from app.retrieval.keyword_retriever import KeywordCandidate
from scripts.benchmark_book_chunking import DEFAULT_DATASET
from scripts.benchmark_chunking import BASELINE_CONFIG
from scripts.compare_adaptive_hybrid import build_comparison, main


def corpus():
    return [KeywordCandidate(1, "a", "needle"), KeywordCandidate(2, "b", "noise")]


def test_manifest_is_sealed_before_first_run_and_v1_remains_unchanged():
    assert experiment_hash_v2() == "0b4a4499d97b6c6fac0e2daa150a5139a114652d20a8e0b5c742665934c0faac"
    verify_registration()
    assert experiment_hash() == "c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9"
    with pytest.raises(FrozenInstanceError):
        ADAPTIVE_POLICIES[1].strength_transform = "sqrt"


def test_manifest_export_cannot_mutate_formulas_policies_or_review_rules():
    manifest = experiment_manifest_v2()
    manifest["reviewRules"]["requiresStrictMacroImprovement"] = False
    manifest["policies"][1]["strength_transform"] = "bad"
    manifest["formula"]["keywordWeight"] = "secret tuning"
    verify_registration()


def test_registration_guard_rejects_mutated_gates(monkeypatch):
    monkeypatch.setitem(__import__("app.evaluation.adaptive_hybrid_experiment", fromlist=["REVIEW_RULES_V2"]).REVIEW_RULES_V2,
                        "requiresStrictMacroImprovement", False)
    with pytest.raises(ValueError, match="seal"):
        verify_registration()


@pytest.mark.parametrize("transform,adapt", [("bad", True), ("identity", "true"), (None, False)])
def test_invalid_policy_variants_fail_before_data_work(transform, adapt):
    with pytest.raises(ValueError):
        AdaptivePolicy("test", transform, adapt)


@pytest.mark.parametrize("raw", [True, -.1, 1.1, math.nan, math.inf, "0.5"])
def test_invalid_strength_cannot_emit_weights(raw):
    with pytest.raises(ValueError):
        AdaptiveDecision("test", raw, .5, 1, 1, 2, .85, .15, .4, .5, .1)


@pytest.mark.asyncio
@pytest.mark.parametrize("policy", ADAPTIVE_POLICIES[1:], ids=lambda policy: policy.id)
@pytest.mark.parametrize("query,expected", [("needle", 1.), ("missing", 0.)])
async def test_endpoint_formulas_have_registered_baseline_and_dense_limits(policy, query, expected):
    resolved = await policy.resolve(query, corpus())
    assert resolved.raw_strength == resolved.effective_strength == expected
    assert resolved.keyword_weight == pytest.approx(.3 * expected)
    assert resolved.vector_weight + resolved.keyword_weight == pytest.approx(1)
    if expected == 1 or not policy.adapt_reranker:
        assert resolved.reranker().weights == {"rrf": .4, "cosine": .5, "lexical": .1}
    else:
        assert resolved.reranker().weights == {"rrf": 0., "cosine": 1., "lexical": 0.}
    assert resolved.distinct_query_tokens == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("policy", ADAPTIVE_POLICIES[1:], ids=lambda policy: policy.id)
async def test_partial_strength_uses_actual_distinct_idf_including_df_zero_and_no_cutoff(policy):
    resolved = await policy.resolve("needle missing", corpus())
    expected_q = math.log(2) / (math.log(2) + math.log(6))
    expected_s = math.sqrt(expected_q) if policy.strength_transform == "sqrt" else expected_q
    assert resolved.raw_strength == pytest.approx(expected_q)
    assert resolved.effective_strength == pytest.approx(expected_s)
    assert resolved.matched_distinct_tokens == 1 and resolved.distinct_query_tokens == 2
    assert resolved.keyword_weight == pytest.approx(.3 * expected_s)
    if policy.adapt_reranker:
        assert resolved.reranker().weights == pytest.approx({"rrf": .4 * expected_s, "cosine": 1 - .5 * expected_s,
                                                          "lexical": .1 * expected_s})
    repeated = await policy.resolve("needle needle missing", corpus())
    assert repeated.raw_strength == pytest.approx(expected_q)


@pytest.mark.asyncio
async def test_strength_uses_last_actual_rewrite_not_question_scaffolding():
    policy = ADAPTIVE_POLICIES[1]
    resolved = await policy.resolve("Where is the needle missing?", corpus())
    assert resolved.focused_variant_index == 1 and resolved.distinct_query_tokens == 2
    assert resolved.raw_strength == pytest.approx(math.log(2) / (math.log(2) + math.log(6)))
    one = await policy.resolve("Where is the needle missing?", corpus(), max_queries=1)
    assert one.focused_variant_index == 0 and one.distinct_query_tokens == 5
    assert one.raw_strength < resolved.raw_strength


@pytest.mark.asyncio
async def test_no_candidate_or_positive_score_strength_zero_without_provider_or_fake_match():
    empty = await ADAPTIVE_POLICIES[3].resolve("needle missing", [])
    assert empty.raw_strength == 0 and empty.matched_distinct_tokens == 0 and empty.distinct_query_tokens == 2
    with pytest.raises(ValueError, match="blank"):
        await ADAPTIVE_POLICIES[1].resolve(" ", corpus())


@pytest.mark.asyncio
async def test_names_labels_metadata_and_vector_ids_cannot_route_or_tune_policy():
    candidates = corpus()
    original = deepcopy(candidates)
    updated = [KeywordCandidate(100 + index, f"different-{index}", item.text,
                               {"expected_answer": "needle", "book_title": "Secret", "language": "vi", "secret": "DO_NOT_EXPORT"})
               for index, item in enumerate(candidates)]
    first = await ADAPTIVE_POLICIES[3].resolve("needle missing", candidates)
    assert first == await ADAPTIVE_POLICIES[3].resolve("needle missing", updated)
    assert candidates == original
    assert "DO_NOT_EXPORT" not in json.dumps(first.as_report())


@pytest.mark.asyncio
@pytest.mark.parametrize("policy", ADAPTIVE_POLICIES[1:], ids=lambda policy: policy.id)
async def test_settings_isolation_public_cosine_and_keyword_only_confidence_preserved(policy):
    resolved = await policy.resolve("needle missing", corpus())
    settings = Settings.model_construct(gemini_api_key="DO_NOT_EXPORT")
    before = settings.model_dump()
    updated = resolved.apply(settings)
    assert settings.model_dump() == before and updated.embedding_version == settings.embedding_version
    assert updated.hybrid_keyword_weight == resolved.keyword_weight
    items = [SearchResult("a", .9, "needle", {"rrf_score": .7}, vector_id="a"),
             SearchResult("b", 0., "needle", {"rrf_score": 1.}, vector_id="b")]
    originals = deepcopy(items)
    ranked = await resolved.reranker().rerank("needle", items, 2)
    assert {row.vector_id: row.score for row in ranked} == {"a": .9, "b": 0.}
    assert items == originals and "DO_NOT_EXPORT" not in json.dumps(resolved.as_report())


def evaluation(recalls=(1., 1.), mrr=(1., 1.), contexts=(1., 1.)):
    cases = [{"id": f"q{index}", "metricsAtK": {"3": {"evidenceRecall": recall}, "5": {"reciprocalRank": reciprocal}},
              "contextRetainedAnchorRate": context}
             for index, (recall, reciprocal, context) in enumerate(zip(recalls, mrr, contexts, strict=True))]
    return {"cases": cases, "metricsAtK": {"3": {"evidenceRecall": sum(recalls) / len(recalls)},
                                          "5": {"reciprocalRank": sum(mrr) / len(mrr)}},
            "contextRetainedAnchorRate": sum(contexts) / len(contexts)}


def segment(**values):
    measured = evaluation(**values)
    return {"datasetId": "en", "bookId": "test", "chunker": "v1",
            "policies": {policy.id: deepcopy(measured) for policy in ADAPTIVE_POLICIES}}


def test_unchanged_candidate_is_not_nominated_without_strict_gain():
    decision = review_policies_v2([segment()])
    assert decision["nomineeForFreshValidation"] is None
    assert all(not row["eligibleForFreshValidation"] for row in decision["reviews"][1:])
    assert decision["runtimePolicy"] == "baseline_v1" and not decision["automaticPromotion"]


def test_coarse_registry_order_breaks_ties_after_all_no_loss_gates():
    group = segment(recalls=(0., 1.), mrr=(0., 1.), contexts=(0., 1.))
    for policy in ADAPTIVE_POLICIES[1:]:
        group["policies"][policy.id] = evaluation()
    decision = review_policies_v2([group])
    assert decision["nomineeForFreshValidation"] == ADAPTIVE_POLICIES[1].id
    assert decision["automaticPromotion"] is False


@pytest.mark.parametrize("loss", ["recall-case", "mrr-segment", "context-segment"])
def test_macro_gains_cannot_hide_case_recall_or_segment_regressions(loss):
    group = segment(recalls=(1., 0.), mrr=(.5, .5), contexts=(.5, .5))
    changes = {"recall-case": {"recalls": (0., 1.)}, "mrr-segment": {"mrr": (0., .5)},
               "context-segment": {"contexts": (0., .5)}}
    group["policies"][ADAPTIVE_POLICIES[1].id] = evaluation(**changes[loss])
    decision = review_policies_v2([group])
    row = decision["reviews"][1]
    assert row["strictMacroImprovement"] and not row["eligibleForFreshValidation"]


def test_fair_case_ids_required_and_mrr_tradeoffs_still_reported():
    group = segment(mrr=(1., .2))
    group["policies"][ADAPTIVE_POLICIES[1].id] = evaluation(mrr=(.8, 1.))
    row = review_policies_v2([group])["reviews"][1]
    assert any(case["delta"]["mrr5"] < 0 for case in row["caseChanges"])
    group["policies"][ADAPTIVE_POLICIES[1].id]["cases"].pop()
    with pytest.raises(ValueError, match="exactly match"):
        review_policies_v2([group])


def local_source():
    book = BookLabel(id="test", title="Test", filename="test.pdf", sha256="a" * 64,
        source_url="https://example.org/test.pdf", rights_url="https://example.org/rights",
        questions=[QuestionLabel(id="q", question="Where is the needle missing?", evidence=[
            EvidenceLabel(page=1, quote="A needle appears here.")])])
    parsed = [ParsedDocument("A needle appears here.", {"page_number": 1}),
              ParsedDocument("Unrelated noise appears elsewhere.", {"page_number": 2})]
    return book, parsed


@pytest.mark.asyncio
async def test_evaluator_injects_actual_request_weights_after_label_free_decision():
    book, parsed = local_source()
    settings = Settings.model_construct(**BASELINE_CONFIG, embedding_dim=2, gemini_api_key="DO_NOT_EXPORT")
    result = build_chunk_result(parsed, book, version="v1", document_id=900001)
    vectors = [[1., 0.], [0., 1.]]
    arguments = dict(document_id=900001, modes=("hybrid",), settings=settings, vectors=vectors,
                     query_provider=SimpleNamespace(embed_query=AsyncMock(return_value=[1., 0.])), collect_ranking_diagnostics=True)
    default = await evaluate_version(book, result, **arguments)
    adaptive = await evaluate_version(book, result, per_query_policy=ADAPTIVE_POLICIES[3], **arguments)
    case = adaptive["retrieval"]["hybrid"]["cases"][0]
    decision, params = case["rankingPolicyDecision"], case["rankingPolicyTraceParameters"]
    assert params["vectorWeight"] == decision["vector_weight"]
    assert params["keywordWeight"] == decision["keyword_weight"]
    assert params["rerankerWeights"] == {"rrf": decision["rank_weight"], "cosine": decision["semantic_weight"], "lexical": decision["lexical_weight"]}
    assert "rankingPolicyDecision" not in default["retrieval"]["hybrid"]["cases"][0]
    assert "DO_NOT_EXPORT" not in json.dumps(adaptive) and book.questions[0].question not in json.dumps(adaptive)
    # Change ONLY labels; same query/text/vectors must produce the same weights.
    changed_book = book.model_copy(update={"questions": [book.questions[0].model_copy(update={"evidence": [
        EvidenceLabel(page=2, quote="Unrelated noise appears elsewhere.") ]})]})
    changed = await evaluate_version(changed_book, result, per_query_policy=ADAPTIVE_POLICIES[3], **arguments)
    other = changed["retrieval"]["hybrid"]["cases"][0]
    assert other["rankingPolicyDecision"] == decision and other["seedCitations"] == case["seedCitations"]
    assert other["metricsAtK"]["5"]["reciprocalRank"] != case["metricsAtK"]["5"]["reciprocalRank"]


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid", ["fixed-reranker", "dense-mode", "limit"])
async def test_offline_hook_rejects_ambiguous_or_unfair_inputs(invalid):
    book, parsed = local_source()
    settings = Settings.model_construct(**BASELINE_CONFIG, embedding_dim=2, keyword_candidate_limit=1 if invalid == "limit" else 2000)
    result = build_chunk_result(parsed, book, version="v1", document_id=900001)
    with pytest.raises(ValueError):
        await evaluate_version(book, result, document_id=900001, modes=("dense",) if invalid == "dense-mode" else ("hybrid",),
            settings=settings, per_query_policy=ADAPTIVE_POLICIES[1], reranker=ADAPTIVE_POLICIES[0].reranker() if invalid == "fixed-reranker" else None)


@pytest.mark.asyncio
async def test_holdout_guard_runs_before_config_reference_or_pdf_work(tmp_path, monkeypatch):
    vi = load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json"))
    vi.baseline_dataset_sha256 = "0" * 64
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.trace_settings", lambda: pytest.fail("No config read"))
    with pytest.raises(ValueError, match="checksum"):
        await build_comparison(load_dataset(DEFAULT_DATASET), vi, tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")


@pytest.mark.asyncio
@pytest.mark.parametrize("missing", ["document", "query"])
async def test_runner_missing_cache_has_no_embedding_fallback(tmp_path, monkeypatch, missing):
    settings = Settings.model_construct(**BASELINE_CONFIG, embedding_dim=2, context_expansion_window=1, gemini_api_key="DO_NOT_EXPORT")
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.trace_settings", lambda: settings)
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.read_reference", lambda *_a: {})
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.parse_pdf", lambda *_a, **_k: [
        ParsedDocument("A synthetic source paragraph for the cache guard.", {"page_number": 1})])
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.CachedGemini.read", lambda _self, task, text:
                        None if task == missing else [1., 0.])
    async def forbidden(*_a, **_k):
        pytest.fail("No embedding/provider fallback allowed")
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.CachedGemini.embed_documents", forbidden)
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.CachedGemini.embed_query", forbidden)
    with pytest.raises(ValueError, match=f"{missing} cache missing"):
        await build_comparison(load_dataset(DEFAULT_DATASET), load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json")),
                               tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")


@pytest.mark.parametrize("suffix", [".json", ".md", ".txt"])
def test_cli_never_overwrites_existing_outputs_or_reads_data_for_invalid_paths(tmp_path, monkeypatch, suffix):
    output = tmp_path / ("report.txt" if suffix == ".txt" else "report.json")
    owned = output if suffix == ".txt" else output.with_suffix(suffix)
    owned.write_text("user-owned", encoding="utf-8")
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.load_dataset", lambda *_a: pytest.fail("No data read"))
    assert main(["--output", str(output)]) == 1 and owned.read_text() == "user-owned"


def test_cli_sanitizes_errors_and_never_writes_partial_failed_reports(tmp_path, monkeypatch, capsys):
    def forbidden(*_a):
        raise RuntimeError("DO_NOT_EXPORT API secret")
    monkeypatch.setattr("scripts.compare_adaptive_hybrid.load_dataset", forbidden)
    output = tmp_path / "report.json"
    assert main(["--output", str(output)]) == 1 and "DO_NOT_EXPORT" not in capsys.readouterr().err
    assert not output.exists() and not output.with_suffix(".md").exists()
