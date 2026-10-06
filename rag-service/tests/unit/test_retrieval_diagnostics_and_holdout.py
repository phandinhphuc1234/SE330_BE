"""Explain real rankings and guard query-level holdout provenance/leakage."""

from copy import deepcopy
import hashlib
import json
from types import SimpleNamespace

import pytest

from app.evaluation.chunking_comparison import (
    AnchoredEvidence, BookLabel, ChapterLabel, ComparisonDataset, EvidenceLabel, QuestionLabel, load_dataset,
    load_holdout_baseline, validate_holdout_anchors,
)
from app.evaluation.retrieval_diagnostics import explain_dense_ranking, explain_keyword_ranking
from app.retrieval.keyword_retriever import KeywordCandidate, bm25_score_candidates, tokenize
from app.core.config import Settings
from app.ingestion.parsers.base import ParsedDocument
from scripts.benchmark_book_chunking import DEFAULT_DATASET, build_report
from scripts.diagnose_chunking_retrieval import build_diagnostics, main


def book(**updates):
    values = dict(id="test", title="Test book", filename="test.pdf", sha256="a" * 64,
                  source_url="https://example.org/test.pdf", rights_url="https://example.org/rights",
                  questions=[QuestionLabel(id="fact", question="Where is the brass key?", evidence=[
                      EvidenceLabel(page=1, quote="The brass key is under the old door.")])])
    return BookLabel(**(values | updates))


def anchor(text="needle", page=1):
    return AnchoredEvidence(page, text, frozenset(range(len(text))))


@pytest.mark.parametrize("query", ["rare needle", "needle needle", "unrelated", ""])
def test_bm25_explanations_reproduce_production_order_and_score_without_mutation(query):
    candidates = [KeywordCandidate(1, "a", "needle rare", {"pageStart": 1, "pageEnd": 1}),
                  KeywordCandidate(2, "b", "common words", {"pageStart": 2, "pageEnd": 2})]
    original = deepcopy(candidates)
    ranges = {"a": [(1, 0, 11)], "b": [(2, 0, 12)]}
    report = explain_keyword_ranking(query, candidates, [anchor()], ranges)
    expected = bm25_score_candidates(tokenize(query), candidates)
    assert [row["vectorId"] for row in report["results"]] == [candidate.vector_id for _, candidate in expected]
    for row, (score, _) in zip(report["results"], expected):
        assert sum(term["contribution"] for term in row["terms"]) == pytest.approx(score)
    assert candidates == original


def test_diagnostic_labels_cannot_influence_keyword_ranking():
    candidates = [KeywordCandidate(1, "a", "needle rare"), KeywordCandidate(2, "b", "needle needle")]
    ranges = {"a": [(1, 0, 11)], "b": [(2, 0, 13)]}
    first = explain_keyword_ranking("needle", candidates, [anchor(page=1)], ranges)
    second = explain_keyword_ranking("needle", candidates, [anchor(page=2)], ranges)
    assert [(row["vectorId"], row["bm25Score"]) for row in first["results"]] == [
        (row["vectorId"], row["bm25Score"]) for row in second["results"]]
    assert first["firstRelevantRank"] != second["firstRelevantRank"]


def test_dense_diagnostics_use_real_cosine_and_keep_relevant_candidate_outside_topk():
    chunks = [SimpleNamespace(text="needle", metadata={"vector_id": "a", "pageStart": 1, "pageEnd": 1}),
              SimpleNamespace(text="noise", metadata={"vector_id": "b", "pageStart": 2, "pageEnd": 2})]
    report = explain_dense_ranking([1., 0.], chunks, [[.6, .8], [1., 0.]], [anchor()],
                                   {"a": [(1, 0, 6)], "b": [(2, 0, 5)]}, top_k=1)
    assert report["firstRelevantRank"] == 2
    assert report["topTwoCosineMargin"] == pytest.approx(.4)
    assert [row["vectorId"] for row in report["results"]] == ["b", "a"]
    with pytest.raises(ValueError, match="real cached"):
        explain_dense_ranking([1., 0.], chunks, [], [anchor()], {})


def baseline_file(tmp_path):
    dataset = ComparisonDataset(name="development", version="1", books=[book()])
    path = tmp_path / "baseline.json"
    path.write_text(dataset.model_dump_json(), encoding="utf-8")
    checksum = hashlib.sha256(path.read_bytes()).hexdigest()
    return path, dataset, checksum


def holdout(original, checksum, **changes):
    held_book = original.books[0].model_copy(update={"questions": [QuestionLabel(
        id="vi-new", question="Ai phụ trách thư viện?", evidence=[EvidenceLabel(page=1, quote="A new source fact.")])]
    })
    return ComparisonDataset(name="holdout", version="1", books=[held_book], split="query_holdout",
                             query_language="vi", baseline_dataset_sha256=checksum, **changes)


def test_holdout_requires_explicit_baseline_checksum():
    with pytest.raises(ValueError, match="pin"):
        ComparisonDataset(name="test", version="1", books=[book()], split="query_holdout")


def test_holdout_checksum_drift_rejected(tmp_path):
    path, original, checksum = baseline_file(tmp_path)
    dataset = holdout(original, checksum)
    assert load_holdout_baseline(dataset, path)["test"].sha256 == "a" * 64
    path.write_text("changed", encoding="utf-8")
    with pytest.raises(ValueError, match="checksum"):
        load_holdout_baseline(dataset, path)


@pytest.mark.parametrize("change", ["id", "query", "source", "title", "chapter", "language"])
def test_holdout_rejects_reused_ids_queries_or_different_source(tmp_path, change):
    path, original, checksum = baseline_file(tmp_path)
    dataset = holdout(original, checksum)
    question = dataset.books[0].questions[0]
    if change == "id":
        question.id = original.books[0].questions[0].id
    elif change == "query":
        question.question = original.books[0].questions[0].question
    elif change == "source":
        dataset.books[0].sha256 = "b" * 64
    elif change == "title":
        dataset.books[0].title = "Altered semantic context"
    elif change == "chapter":
        dataset.books[0].chapters = [ChapterLabel(page=1, heading="Chapter 1", title="Chapter 1")]
    else:
        dataset.source_language = "vi"
    with pytest.raises(ValueError):
        load_holdout_baseline(dataset, path)


@pytest.mark.parametrize("quote", ["The brass key is under the old door.", "brass key", "The brass key"])
def test_translated_or_reworded_query_cannot_reuse_development_evidence(quote):
    original = book()
    held = book(questions=[QuestionLabel(id="new", question="Chìa khóa ở đâu?", evidence=[EvidenceLabel(page=1, quote=quote)])])
    with pytest.raises(ValueError, match="overlaps"):
        validate_holdout_anchors(held, original, {1: "The brass key is under the old door. A new source fact."})


def test_disjoint_evidence_on_same_page_is_valid():
    original = book()
    held = book(questions=[QuestionLabel(id="new", question="Thông tin mới là gì?", evidence=[
        EvidenceLabel(page=1, quote="A new source fact.")])])
    validate_holdout_anchors(held, original, {1: "The brass key is under the old door. A new source fact."})


def test_checked_vi_manifest_is_new_queries_not_a_claim_of_vietnamese_source_quality():
    path = DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json")
    dataset = load_dataset(path)
    originals = load_holdout_baseline(dataset, DEFAULT_DATASET)
    assert dataset.query_language == "vi" and dataset.source_language == "en"
    assert dataset.split == "query_holdout" and dataset.review_status == "assistant_source_checked"
    assert len(dataset.books) == 2 and sum(len(book.questions) for book in dataset.books) == 20
    assert sum(len(question.evidence) for book in dataset.books for question in book.questions) == 22
    assert all("vi" in question.tags for book in dataset.books for question in book.questions)
    assert set(originals) == {book.id for book in dataset.books}


def test_first_evaluated_query_holdout_manifest_is_frozen():
    # These are the hashes recorded by the first evaluation, not tuned labels.
    # Changes require a new dataset version and a fresh independent test set.
    path = DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json")
    assert hashlib.sha256(path.read_bytes()).hexdigest() == "8e21b12b86a26a8f753cba1b1ce1888be2c27d80f04bfbd41be39b78f0dc6318"
    canonical_model = json.dumps(load_dataset(path).model_dump(), ensure_ascii=False, sort_keys=True).encode()
    assert hashlib.sha256(canonical_model).hexdigest() == "cdf290fccdf15dba5d5932bee428000302195975964b7ba5f306112264ab3718"


def test_diagnostic_cli_rejects_overwrite_before_loading_dataset(tmp_path, monkeypatch):
    output = tmp_path / "keep.json"
    output.write_text("user-owned", encoding="utf-8")
    monkeypatch.setattr("scripts.diagnose_chunking_retrieval.load_dataset", lambda *_: pytest.fail("No input read"))
    assert main(["--output", str(output)]) == 1
    assert output.read_text() == "user-owned"


@pytest.mark.asyncio
async def test_unknown_diagnostic_case_is_rejected_before_pdf_or_provider_work(tmp_path, monkeypatch):
    monkeypatch.setattr("scripts.diagnose_chunking_retrieval.parse_pdf", lambda *_a, **_k: pytest.fail("No PDF I/O"))
    dataset = ComparisonDataset(name="test", version="1", books=[book()])
    with pytest.raises(ValueError, match="case ID"):
        await build_diagnostics(dataset, tmp_path, book_id="test", case_ids=("missing",))


@pytest.mark.asyncio
async def test_holdout_guard_precedes_pdf_parse_and_provider(tmp_path, monkeypatch):
    path, original, checksum = baseline_file(tmp_path)
    dataset = holdout(original, checksum)
    dataset.baseline_dataset_sha256 = "0" * 64
    monkeypatch.setattr("scripts.benchmark_book_chunking.DEFAULT_DATASET", path)
    monkeypatch.setattr("scripts.benchmark_book_chunking.parse_pdf", lambda *_a, **_k: pytest.fail("No PDF I/O"))
    with pytest.raises(ValueError, match="checksum"):
        await build_report(dataset, tmp_path, gemini=True)


@pytest.mark.asyncio
async def test_cache_only_diagnostics_fail_closed_without_any_embedding_call(tmp_path, monkeypatch):
    monkeypatch.setattr("scripts.diagnose_chunking_retrieval.parse_pdf", lambda *_a, **_k: [
        ParsedDocument("Chapter 1\n\nThe brass key is under the old door.", {"page_number": 1})])
    monkeypatch.setattr("scripts.diagnose_chunking_retrieval.get_settings", lambda: Settings.model_construct(
        embedding_dim=2, gemini_api_key="DO_NOT_SERIALIZE"))
    async def forbidden(*_a, **_k):
        pytest.fail("Diagnostics must never call an embedding provider.")
    monkeypatch.setattr("scripts.diagnose_chunking_retrieval.CachedGemini.embed_documents", forbidden)
    monkeypatch.setattr("scripts.diagnose_chunking_retrieval.CachedGemini.embed_query", forbidden)
    dataset = ComparisonDataset(name="test", version="1", books=[book()])
    with pytest.raises(ValueError, match="document embedding cache missing"):
        await build_diagnostics(dataset, tmp_path, book_id="test", case_ids=("fact",), cached_dense=True)
