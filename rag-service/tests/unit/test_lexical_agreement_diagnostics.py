"""Read-only lexical explanations cannot become a hidden ranking policy."""

from copy import deepcopy
import json
import math
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.evaluation.chunking_comparison import (
    AnchoredEvidence, BookLabel, ComparisonDataset, EvidenceLabel, LocalCandidateSource, LocalVectorStore,
    QuestionLabel, build_chunk_result, cosine, evaluate_version, load_dataset,
)
from app.evaluation.lexical_agreement_diagnostics import describe_lexical_agreement, lexical_features, summarize_cases
from app.evaluation.retrieval_diagnostics import profile_keyword_corpus
from app.ingestion.parsers.base import ParsedDocument
from app.retrieval.keyword_retriever import KeywordCandidate, KeywordRetriever, bm25_score_candidates, tokenize
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalRequest, LibraryVectorRetrievalService
from app.retrieval.query_rewriter import QueryRewriter
from app.retrieval.ranking_trace import RankingTrace
from app.retrieval.retrieval_pipeline import RetrievalPipeline
from scripts.benchmark_book_chunking import DEFAULT_DATASET
from scripts.benchmark_chunking import BASELINE_CONFIG
from scripts.diagnose_lexical_agreement import build_report, main


@pytest.mark.parametrize("query", ["needle rare", "needle needle", "không", "", "Della's watch", "needle missing"])
@pytest.mark.parametrize("texts", [["needle rare", "needle needle", "noise"], ["", ""]])
def test_full_corpus_profiles_match_actual_scores_ranks_and_keep_zero_rows(query, texts):
    candidates = [KeywordCandidate(index, str(index), text) for index, text in enumerate(texts)]
    original = deepcopy(candidates)
    profile = profile_keyword_corpus(query, candidates)
    expected = {candidate.vector_id: (rank, score) for rank, (score, candidate) in enumerate(
        bm25_score_candidates(tokenize(query), candidates), 1)}
    assert len(profile["results"]) == len(candidates)
    for row in profile["results"]:
        rank, score = expected.get(row["vectorId"], (None, 0.))
        assert row["rank"] == rank and row["bm25Score"] == pytest.approx(score)
        assert sum(term["contribution"] for term in row["terms"]) == pytest.approx(score)
    assert candidates == original


def test_coverage_is_distinct_token_based_but_bm25_preserves_query_frequency():
    candidates = [KeywordCandidate(1, "a", "douglass key"), KeywordCandidate(2, "b", "noise")]
    row = profile_keyword_corpus("douglass key key missing", candidates)["results"][0]
    original = deepcopy(row)
    features = lexical_features(row, {"douglass"})
    assert features["queryTokenCoverage"] == pytest.approx(2 / 3)
    idfs = {term["term"]: term["idf"] for term in row["terms"]}
    assert features["idfWeightedQueryCoverage"] == pytest.approx((idfs["douglass"] + idfs["key"]) / sum(idfs.values()))
    assert features["idfWeightedQueryCoverage"] < 2 / 3  # DF=0 is not silently excluded.
    assert features["matchedTitleTerms"] == ["douglass"]
    assert features["matchedNonTitleTerms"] == ["key"]
    assert features["titleContributionFraction"] == pytest.approx(1 / 3)
    assert sum(term["contributionFraction"] for term in features["terms"]) == pytest.approx(1)
    assert row == original


def test_zero_score_is_not_a_positive_rank_or_fake_coverage():
    row = profile_keyword_corpus("missing", [KeywordCandidate(1, "a", "noise")])["results"][0]
    features = lexical_features(row, {"missing"})
    assert features["bm25CorpusRank"] is None and features["bm25Score"] == 0
    assert features["queryTokenCoverage"] == features["idfWeightedQueryCoverage"] == features["titleContributionFraction"] == 0
    assert features["matchedTerms"] == []


def test_title_membership_does_not_remove_repeated_meaningful_body_terms():
    candidates = [KeywordCandidate(1, "a", "Douglass Douglass Douglass")]
    row = profile_keyword_corpus("Douglass", candidates)["results"][0]
    feature = lexical_features(row, {"douglass"})
    assert feature["terms"][0]["tf"] == 3 and feature["bm25Score"] > 0
    assert feature["titleContributionFraction"] == 1  # A description, not a scoring change.


@pytest.mark.parametrize("k1,b", [(0., 0.), (1., 1.), (2., .5), (1.5, .75)])
def test_term_explanations_check_nondefault_bm25_parameters(k1, b):
    candidates = [KeywordCandidate(1, "a", "needle " * 9), KeywordCandidate(2, "b", "needle noise")]
    rows = profile_keyword_corpus("needle needle noise", candidates, k1=k1, b=b)["results"]
    expected = {candidate.vector_id: score for score, candidate in bm25_score_candidates(
        tokenize("needle needle noise"), candidates, k1=k1, b=b)}
    assert all(row["bm25Score"] == pytest.approx(expected[row["vectorId"]]) for row in rows)


@pytest.fixture
async def diagnostic_inputs():
    settings = Settings.model_construct(embedding_dim=2, embedding_version="unit-v1", hybrid_fail_open=False)
    texts = ["Douglass brass key key", "Douglass common prose", "unrelated vocabulary"]
    chunks = [SimpleNamespace(text=text, metadata={"vector_id": str(index), "document_id": 1, "ebook_id": 1,
              "sourceType": "LIBRARY_EBOOK", "embedding_version": "unit-v1", "active": True,
              "chunk_index": index, "pageStart": index + 1, "pageEnd": index + 1})
              for index, text in enumerate(texts)]
    candidates = [KeywordCandidate(index, str(index), chunk.text, dict(chunk.metadata)) for index, chunk in enumerate(chunks)]
    vectors = [[.8, .6], [1., 0.], [.6, .8]]
    query = "Where did Douglass hide the brass key key?"
    pipeline = RetrievalPipeline(settings=settings, vector_retriever=LibraryVectorRetrievalService(settings=settings,
        embedding_provider=SimpleNamespace(embed_query=AsyncMock(return_value=[1., 0.])), vector_store=LocalVectorStore(chunks, vectors)),
        keyword_retriever=KeywordRetriever(settings=settings, candidate_source=LocalCandidateSource(chunks)))
    trace = RankingTrace()
    request = LibraryVectorRetrievalRequest(query, document_id=1, ebook_id=1, top_k=5, retrieval_mode="hybrid", expand_context=False)
    assert await pipeline.search(request, trace=trace) == await pipeline.search(request)
    variants = await QueryRewriter().rewrite(query)
    scores = {str(index): cosine([1., 0.], vector) for index, vector in enumerate(vectors)}
    ranges = {str(index): [(index + 1, 0, len(text))] for index, text in enumerate(texts)}
    anchors = [AnchoredEvidence(1, "Douglass", frozenset(range(8)))]
    return variants, candidates, "Life of Douglass", trace, scores, anchors, ranges


@pytest.mark.asyncio
async def test_actual_pipeline_branch_scores_labels_and_inputs_are_invariant(diagnostic_inputs):
    values = diagnostic_inputs
    original = deepcopy((values[1], values[3].export(), values[4]))
    first = describe_lexical_agreement(*values)
    changed = list(values)
    changed[5] = [AnchoredEvidence(2, "Douglass", frozenset(range(8)))]
    second = describe_lexical_agreement(*changed)
    def label_free(rows):
        return {row["vectorId"]: {key: value for key, value in row.items() if key != "evidence"} for row in rows}
    assert label_free(first["candidates"]) == label_free(second["candidates"])
    assert first["trace"]["stageSummary"]["dense_candidates"]["firstRelevantRank"] != second["trace"]["stageSummary"]["dense_candidates"]["firstRelevantRank"]
    assert (values[1], values[3].export(), values[4]) == original
    assert "Douglass brass key key" not in json.dumps(first)  # No chunk/source text.


@pytest.mark.asyncio
@pytest.mark.parametrize("drift", ["bm25-score", "bm25-order", "dense-score", "dense-order", "variants", "missing-vector", "nonfinite", "duplicate-id"])
async def test_diagnostic_fails_closed_on_actual_branch_or_input_drift(diagnostic_inputs, drift):
    values = deepcopy(list(diagnostic_inputs))
    trace = values[3]
    if drift == "bm25-score":
        trace.stages["bm25_0"][0]["bm25RawScore"] += 1
    elif drift == "bm25-order":
        trace.stages["bm25_0"].reverse()
    elif drift == "dense-score":
        values[4]["0"] += .01
    elif drift == "dense-order":
        trace.stages["dense_candidates"].reverse()
    elif drift == "variants":
        values[0].pop()
    elif drift == "missing-vector":
        values[4].pop("0")
    elif drift == "duplicate-id":
        values[1].append(values[1][0])
    else:
        values[4]["0"] = math.nan
    with pytest.raises(ValueError):
        describe_lexical_agreement(*values)


@pytest.mark.asyncio
async def test_scoped_bm25_zero_evidence_remains_visible(diagnostic_inputs):
    values = list(diagnostic_inputs)
    values[5] = [AnchoredEvidence(3, "unrelated", frozenset(range(9)))]
    report = describe_lexical_agreement(*values)
    zero = next(row for row in report["candidates"] if row["vectorId"] == "2")
    assert zero["evidence"]["evidenceRecall"] == 1
    assert zero["lexical"]["bm25_0"]["bm25Score"] == 0
    assert zero["lexical"]["bm25_1"]["bm25CorpusRank"] is None
    assert zero["stageRanks"]["bm25_0"] is None


@pytest.mark.asyncio
async def test_summary_counts_all_cases_without_claiming_title_terms_are_noise(diagnostic_inputs):
    diagnostic = describe_lexical_agreement(*diagnostic_inputs)
    case = {"datasetId": "test", "bookId": "test", "chunker": "v1", "diagnostic": diagnostic}
    summaries = summarize_cases([case, deepcopy(case)])
    assert summaries[0]["caseCount"] == 2
    assert sum(summaries[0]["hybridVsDenseRecall3"].values()) == 2
    assert summaries[0]["focusedBm25Top1TitleTokenOnlyCount"] == 0


@pytest.mark.asyncio
async def test_holdout_checksum_guard_precedes_config_pdf_reference_or_cache(tmp_path, monkeypatch):
    english = load_dataset(DEFAULT_DATASET)
    vi = load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json"))
    vi.baseline_dataset_sha256 = "0" * 64
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.trace_settings", lambda: pytest.fail("No config read"))
    with pytest.raises(ValueError, match="checksum"):
        await build_report(english, vi, tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")


@pytest.mark.asyncio
@pytest.mark.parametrize("missing", ["document", "query"])
async def test_missing_cache_cannot_fall_through_to_provider(tmp_path, monkeypatch, missing):
    english = load_dataset(DEFAULT_DATASET)
    vi = load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json"))
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.trace_settings", lambda: Settings.model_construct(
        **BASELINE_CONFIG, embedding_dim=2, context_expansion_window=1, gemini_api_key="DO_NOT_EXPORT"))
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.read_reference", lambda *_a: {})
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.parse_pdf", lambda *_a, **_k: [
        ParsedDocument("A short source paragraph for the cache guard test.", {"page_number": 1})])
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.CachedGemini.read", lambda _self, task, text:
                        None if task == missing else [1., 0.])
    async def forbidden(*_a, **_k):
        pytest.fail("No embedding/provider fallback allowed")
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.CachedGemini.embed_documents", forbidden)
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.CachedGemini.embed_query", forbidden)
    with pytest.raises(ValueError, match=f"{missing} cache missing"):
        await build_report(english, vi, tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")


@pytest.mark.parametrize("suffix", [".json", ".md", ".txt"])
def test_cli_never_clobbers_outputs_or_reads_data_when_paths_invalid(tmp_path, monkeypatch, suffix):
    output = tmp_path / ("report.txt" if suffix == ".txt" else "report.json")
    owned = output if suffix == ".txt" else output.with_suffix(suffix)
    owned.write_text("user-owned", encoding="utf-8")
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.load_dataset", lambda *_a: pytest.fail("No data read"))
    assert main(["--output", str(output)]) == 1
    assert owned.read_text() == "user-owned"


def test_cli_does_not_print_raw_errors_secrets_or_write_failed_reports(tmp_path, monkeypatch, capsys):
    def forbidden(*_a):
        raise RuntimeError("DO_NOT_EXPORT secret material")
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.load_dataset", forbidden)
    output = tmp_path / "report.json"
    assert main(["--output", str(output)]) == 1
    assert "DO_NOT_EXPORT" not in capsys.readouterr().err
    assert not output.exists() and not output.with_suffix(".md").exists()


@pytest.mark.asyncio
@pytest.mark.parametrize("drift", ["fusion", "candidate-budget"])
async def test_runner_rejects_scoring_drift_before_reference_pdf_or_cache(tmp_path, monkeypatch, drift):
    settings = Settings.model_construct(**BASELINE_CONFIG, context_expansion_window=1)
    settings = settings.model_copy(update={"hybrid_vector_weight": .85} if drift == "fusion"
                                   else {"hybrid_candidate_multiplier": 4})
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.trace_settings", lambda: settings)
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.read_reference", lambda *_a: pytest.fail("No reference read"))
    with pytest.raises(ValueError, match="unchanged baseline"):
        await build_report(load_dataset(DEFAULT_DATASET), load_dataset(DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json")),
                           tmp_path, english_reference=tmp_path / "a", vi_reference=tmp_path / "b")


@pytest.mark.asyncio
async def test_full_runner_replays_synthetic_references_and_whitelists_private_report(tmp_path, monkeypatch):
    # Synthetic vectors are only for this UNIT test, not a quality benchmark.
    settings = Settings.model_construct(**BASELINE_CONFIG, embedding_dim=2, embedding_version="unit-v1",
                                        context_expansion_window=1, gemini_api_key="RUNTIME_KEY_NEVER_EXPORT_4")
    parsed = [ParsedDocument("The brass key is beside the old door. A visitor finds the brass key.", {"page_number": 1})]
    books = [BookLabel(id="unit", title="The Key", filename="unit.pdf", sha256="a" * 64,
                      source_url="https://example.org/unit.pdf", rights_url="https://example.org/rights",
                      questions=[QuestionLabel(id=language, question=query, evidence=[
                          EvidenceLabel(page=1, quote="The brass key is beside the old door.")])])
             for language, query in (("en", "Where is the brass key?"), ("vi", "Chìa khóa được đặt ở đâu?"))]
    datasets = [ComparisonDataset(name=language, version="1", query_language=language, books=[book])
                for language, book in zip(("en", "vi"), books, strict=True)]
    references = {}
    for dataset in datasets:
        versions = {}
        for chunker in ("v1", "v2"):
            result = build_chunk_result(parsed, dataset.books[0], version=chunker, document_id=900001)
            versions[chunker] = await evaluate_version(dataset.books[0], result, document_id=900001,
                modes=("hybrid",), settings=settings, vectors=[[1., 0.] for _chunk in result.chunks],
                query_provider=SimpleNamespace(embed_query=AsyncMock(return_value=[1., 0.])))
        references[dataset.query_language] = {"unit": {"versions": versions}}
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.trace_settings", lambda: settings)
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.load_holdout_baseline", lambda *_a: {})
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.read_reference", lambda _path, dataset, _settings: references[dataset.query_language])
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.parse_pdf", lambda *_a, **_k: deepcopy(parsed))
    monkeypatch.setattr("scripts.diagnose_lexical_agreement.CachedGemini.read", lambda *_a: [1., 0.])
    reference_path = tmp_path / "reference.json"
    reference_path.write_text("{}", encoding="utf-8")
    report = await build_report(*datasets, tmp_path, english_reference=reference_path, vi_reference=reference_path)
    assert report["caseCount"] == report["baselineHybridReplayCaseCount"] == 4
    assert all(case["baselineAllCaseFieldsEqual"] and case["traceInvariantVerified"] for case in report["cases"])
    assert report["providerCalls"] == 0 and not report["runtimeWrites"] and not report["automaticPromotion"]
    serialized = json.dumps(report, ensure_ascii=False)
    assert "RUNTIME_KEY_NEVER_EXPORT_4" not in serialized
    assert parsed[0].text not in serialized and "Where is the brass key?" not in serialized
