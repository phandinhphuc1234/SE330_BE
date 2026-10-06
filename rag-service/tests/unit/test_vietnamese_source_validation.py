"""Fresh-language provenance, immutable labels, honest gates and no paid I/O."""

from dataclasses import FrozenInstanceError
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.evaluation.chunking_comparison import BookLabel, EvidenceLabel, QuestionLabel, load_dataset
from app.evaluation.vietnamese_source_validation import (
    ADAPTIVE_POLICY_SHA256, CONTEXT_BUDGET, DATASET_PATH, DATASET_SHA256,
    EMBEDDING_IDENTITY, FIXED_POLICY_SHA256, SOURCE_SHA256, EmbeddingIdentity,
    inspect_cache, registration_manifest, verify_registration,
)
from app.ingestion.parsers.base import ParsedDocument
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_DATASET, build_report
from scripts.benchmark_vietnamese_source import build_validation_report, cache_preflight, main, markdown_summary


def test_fresh_source_labels_are_sealed_and_not_the_old_query_holdout():
    dataset = verify_registration()
    previous = load_dataset(DEFAULT_DATASET)
    assert hashlib.sha256(DATASET_PATH.read_bytes()).hexdigest() == DATASET_SHA256
    assert dataset.split == "development" and dataset.source_language == dataset.query_language == "vi"
    assert dataset.review_status == "assistant_source_checked"
    assert len(dataset.books[0].questions) == 20
    assert sum(len(q.evidence) for q in dataset.books[0].questions) == 23
    assert dataset.books[0].sha256 == SOURCE_SHA256
    assert SOURCE_SHA256 not in {b.sha256 for b in previous.books}
    assert all(q.question not in {old.question for b in previous.books for old in b.questions}
               for q in dataset.books[0].questions)
    assert {q.id for q in dataset.books[0].questions} & {q.id for b in previous.books for q in b.questions} == set()
    assert any("multi-page" in q.tags and len({e.page for e in q.evidence}) > 1 for q in dataset.books[0].questions)
    assert any("footnote" in q.tags for q in dataset.books[0].questions)


def test_owner_review_receipt_matches_every_label_and_preserves_historical_provenance():
    from datetime import datetime, timezone

    dataset = verify_registration()
    review = json.loads(DATASET_PATH.with_name("vietnamese_source_owner_review_v1.json").read_text(encoding="utf-8"))
    book = dataset.books[0]
    assert review["schema_version"] == 1 and review["decision"] == "approved"
    assert review["reviewer_role"] == "project_owner"
    assert review["confirmation_text"] == "mình duyệt hết nha"
    assert review["dataset_file"] == DATASET_PATH.name
    assert review["dataset_file_sha256"] == DATASET_SHA256 and review["source_pdf_sha256"] == SOURCE_SHA256
    assert review["approved_question_ids"] == [q.id for q in book.questions]
    assert review["approved_evidence_anchor_count"] == sum(len(q.evidence) for q in book.questions) == 23
    assert review["approved_section_pages"] == [section.page for section in book.chapters]
    assert review["review_timing"] == "after_first_bm25_evaluation"
    recorded = datetime.fromisoformat(review["recorded_at_utc"].replace("Z", "+00:00"))
    assert recorded.utcoffset() == timezone.utc.utcoffset(recorded)
    for flag in ("independent_blind_expert_review_claim", "label_changes", "historical_report_changes",
                 "automatic_promotion_authorized", "provider_calls_authorized_by_this_receipt"):
        assert review[flag] is False
    # The new receipt does not rewrite the provenance at first scoring.
    assert dataset.review_status == "assistant_source_checked"
    assert registration_manifest()["humanReview"] == "pending"


@pytest.mark.parametrize("mutation", ["question", "quote", "page", "review", "language", "source", "whitespace"])
def test_seal_rejects_source_question_and_provenance_drift_before_parsing(tmp_path, mutation):
    payload = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    if mutation == "question":
        payload["books"][0]["questions"][0]["question"] += " changed"
    elif mutation == "quote":
        payload["books"][0]["questions"][0]["evidence"][0]["quote"] += " changed"
    elif mutation == "page":
        payload["books"][0]["questions"][0]["evidence"][0]["page"] += 1
    elif mutation == "review":
        payload["review_status"] = "human_reviewed"
    elif mutation == "language":
        payload["source_language"] = "en"
    elif mutation == "source":
        payload["books"][0]["sha256"] = "a" * 64
    path = tmp_path / "labels.json"
    path.write_text(json.dumps(payload, ensure_ascii=False) + ("\n" if mutation == "whitespace" else ""), encoding="utf-8")
    with pytest.raises(ValueError, match="labels drifted"):
        verify_registration(path)


def test_old_fixed_policy_drift_is_not_accepted(monkeypatch):
    monkeypatch.setattr("app.evaluation.vietnamese_source_validation.experiment_hash", lambda: "changed")
    with pytest.raises(ValueError, match="fixed policies drifted"):
        verify_registration()


def test_old_adaptive_policy_drift_is_not_accepted(monkeypatch):
    def reject():
        raise ValueError("adaptive seal drifted")
    monkeypatch.setattr("app.evaluation.vietnamese_source_validation.verify_adaptive_seal", reject)
    with pytest.raises(ValueError, match="adaptive seal"):
        verify_registration()


def test_registration_never_claims_human_review_blind_reuse_or_native_authorship():
    registration = registration_manifest()
    assert registration["referencePolicies"] == {"fixedV1Sha256": FIXED_POLICY_SHA256, "adaptiveV2Sha256": ADAPTIVE_POLICY_SHA256}
    assert registration["humanReview"] == "pending"
    assert registration["policiesEvaluated"] == ["bm25-control"]
    assert registration["originalVietnameseAuthorshipClaim"] is False
    assert registration["automaticPromotion"] is registration["providerCallsAllowed"] is registration["runtimeWrites"] is False
    assert "regression after observation" in registration["usage"]
    registration["referencePolicies"]["fixedV1Sha256"] = "changed"
    assert registration_manifest()["referencePolicies"]["fixedV1Sha256"] == FIXED_POLICY_SHA256


def test_embedding_identity_is_public_frozen_and_does_not_load_env(monkeypatch):
    monkeypatch.setattr("app.core.config.get_settings", lambda: pytest.fail("No .env read"))
    settings = EMBEDDING_IDENTITY.settings()
    assert settings.embedding_model == "gemini-embedding-2" and settings.embedding_dim == 3072
    assert settings.embedding_version == "gemini-embedding-2-3072-v1"
    assert settings.context_expansion_window == 1 and settings.hybrid_fail_open is False
    with pytest.raises(FrozenInstanceError):
        EMBEDDING_IDENTITY.dimension = 2
    assert "api_key" not in json.dumps(EMBEDDING_IDENTITY.report())


def test_cache_inspection_deduplicates_exact_task_and_text_and_never_embeds():
    calls = []
    def read(task, text):
        calls.append((task, text))
        return [1, 0] if task == "document" else None
    cache = SimpleNamespace(read=read, embed_query=lambda *_: pytest.fail("No fallback"),
                            embed_documents=lambda *_: pytest.fail("No fallback"))
    result = inspect_cache([("document", "source text"), ("document", "source text"), ("query", "source text")], cache)
    assert len(calls) == 2 and result["uniqueInputCount"] == 2 and result["missingInputCount"] == 1
    assert result["status"] == "blocked_missing_real_cache" and result["providerCalls"] == 0
    assert result["denseHybridScored"] is False and "source text" not in json.dumps(result)
    assert result["byTask"]["document"] == {"uniqueInputs": 1, "cacheHits": 1, "missingInputs": 0}


@pytest.mark.parametrize("inputs", [[("other", "x")], [("query", "")], [("document", 10)]])
def test_preflight_rejects_invalid_inputs_before_cache_read(inputs):
    with pytest.raises(ValueError, match="nonblank"):
        inspect_cache(inputs, SimpleNamespace(read=lambda *_: pytest.fail("No cache read")))


def test_all_cached_is_readiness_not_quality_evaluation():
    result = inspect_cache([("query", "q")], SimpleNamespace(read=lambda *_: [1, 0]))
    assert result["status"] == "ready" and result["missingInputCount"] == 0
    assert result["denseHybridScored"] is False


@pytest.mark.parametrize("vector", [[0, 0], [float("nan"), 1], [1], [True, 1]])
def test_corrupt_real_cache_does_not_count_as_missing_or_fall_back(tmp_path, vector):
    cache = CachedGemini(None, cache_dir=tmp_path, settings=EmbeddingIdentity(dimension=2).settings(), interval=0)
    key = cache.key("query", "q")
    (tmp_path / f"embedding-{key}.json").write_text(json.dumps({"key": key, "vector": vector}), encoding="utf-8")
    with pytest.raises(ValueError):
        inspect_cache([("query", "q")], cache)


def sample_book():
    return BookLabel(id="synthetic", title="Synthetic test only", filename="synthetic.pdf", sha256="a" * 64,
                     source_url="https://example.org/synthetic.pdf", rights_url="https://example.org/rights",
                     questions=[QuestionLabel(id="q", question="Where is the brass key?", evidence=[
                         EvidenceLabel(page=1, quote="The brass key is under the old door.")])])


def test_preflight_uses_real_chunking_but_no_provider_storage_database_or_settings(tmp_path, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("No provider/runtime I/O or .env access")
    for target in ("app.core.config.get_settings", "app.ingestion.pipeline.async_session_factory",
                   "app.ingestion.pipeline.IngestionPipeline.run", "app.ingestion.pipeline.IngestionPipeline._index_chunks",
                   "app.ingestion.artifacts.writer.get_object_storage_adapter", "app.indexing.providers.GeminiEmbeddingProvider"):
        monkeypatch.setattr(target, forbidden)
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.parse_pdf", lambda *args, **kwargs: [
        ParsedDocument("The brass key is under the old door.", {"page_number": 1})])
    report = cache_preflight(SimpleNamespace(books=[sample_book()]), tmp_path)
    assert report["byTask"]["query"]["uniqueInputs"] == 1
    assert report["byTask"]["document"]["uniqueInputs"] >= 1
    assert report["missingInputCount"] == report["uniqueInputCount"] and report["providerCalls"] == 0


def test_preflight_validates_anchors_before_embedding_cache_checks(tmp_path, monkeypatch):
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.parse_pdf", lambda *args, **kwargs: [
        ParsedDocument("Wrong source evidence.", {"page_number": 1})])
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.inspect_cache", lambda *_: pytest.fail("Anchor gate first"))
    with pytest.raises(ValueError, match="exactly once"):
        cache_preflight(SimpleNamespace(books=[sample_book()]), tmp_path)


@pytest.mark.asyncio
async def test_generic_benchmark_uses_actual_document_count_and_language(tmp_path, monkeypatch):
    from app.evaluation.chunking_comparison import ComparisonDataset
    monkeypatch.setattr("scripts.benchmark_book_chunking.parse_pdf", lambda *args, **kwargs: [
        ParsedDocument("The brass key is under the old door.", {"page_number": 1})])
    report = await build_report(ComparisonDataset(name="synthetic", version="1", source_language="vi",
                                                  query_language="vi", books=[sample_book()]), tmp_path)
    assert "1 source document(s)" in report["limitations"][0]
    assert "source language=vi, query language=vi" in report["limitations"][0]
    assert "English" not in report["limitations"][0]


def stub_report():
    return {"generatedAt": "test", "dataset": {}, "books": [{"id": "test", "versions": {
        version: {"chunkCount": 1, "nonWhitespaceSourceCoverage": 1, "chapterAudit": {"chapterLabelMismatchCount": 1},
                  "retrieval": {"bm25": {"caseCount": 20, "metricsAtK": {"3": {"evidenceRecall": .5},
                      "5": {"reciprocalRank": .5}}, "contextRetainedAnchorRate": .5}}} for version in ("v1", "v2")}}],
        "errorCount": 0, "decision": {"gates": {"denseHybridCompleted": False}, "recommendation": "old"},
        "limitations": ["old"], "runtimeWrites": False, "llmGeneration": False}


@pytest.mark.asyncio
async def test_report_keeps_missing_modes_review_and_runtime_honest(tmp_path, monkeypatch):
    build = AsyncMock(return_value=stub_report())
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.build_report", build)
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.cache_preflight", lambda *_: EMBEDDING_IDENTITY.report() | inspect_cache(
        [("document", "DO_NOT_EXPORT_SOURCE"), ("query", "DO_NOT_EXPORT_QUERY")], SimpleNamespace(read=lambda *_: None)))
    report = await build_validation_report(tmp_path)
    assert build.call_args.kwargs == {"gemini": False, "context_budget": CONTEXT_BUDGET}
    assert report["caseCount"] == 40 and report["providerCalls"] == 0
    assert report["registration"]["humanReview"] == "pending" and report["automaticPromotion"] is False
    assert not report["decision"]["gates"]["humanReviewCompleted"]
    assert not report["decision"]["gates"]["denseHybridCompleted"]
    assert report["decision"]["gates"]["realEmbeddingCacheReady"] is False
    assert "HOLD v1" in report["decision"]["recommendation"]
    assert "DO_NOT_EXPORT" not in json.dumps(report)
    text = markdown_summary(report)
    assert "Human review: pending" in text and "NOT quality evaluation" in text
    assert "Provider calls: 0" in text


@pytest.mark.asyncio
async def test_registration_is_checked_before_and_after_scoring(tmp_path, monkeypatch):
    calls = []
    def verify(path):
        calls.append(path)
        if len(calls) == 2:
            raise ValueError("labels drifted during scoring")
        return verify_registration()
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.verify_registration", verify)
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.build_report", AsyncMock(return_value=stub_report()))
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.cache_preflight", lambda *_: {"status": "ready"})
    with pytest.raises(ValueError, match="during scoring"):
        await build_validation_report(tmp_path)
    assert len(calls) == 2


@pytest.mark.parametrize("suffix", [".json", ".md"])
def test_cli_preserves_outputs_and_checks_collision_before_scoring(tmp_path, monkeypatch, suffix):
    output = tmp_path / "report.json"
    existing = output.with_suffix(suffix)
    existing.write_text("keep user artifact", encoding="utf-8")
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.build_validation_report", lambda *_: pytest.fail("No scoring"))
    assert main(["--output", str(output)]) == 1
    assert existing.read_text(encoding="utf-8") == "keep user artifact"


def test_cli_redacts_raw_failures(tmp_path, monkeypatch, capsys):
    async def fail(*args, **kwargs):
        raise ValueError("DO_NOT_EXPORT_SECRET")
    monkeypatch.setattr("scripts.benchmark_vietnamese_source.build_validation_report", fail)
    output = tmp_path / "report.json"
    assert main(["--output", str(output)]) == 1
    captured = capsys.readouterr()
    assert "DO_NOT_EXPORT_SECRET" not in captured.out + captured.err
    assert not output.exists()


def test_cli_has_no_paid_provider_opt_in(tmp_path):
    with pytest.raises(SystemExit):
        main(["--gemini", "--output", str(tmp_path / "report.json")])
