"""Owner approval, hard input budget, no-retry calls and real-cache-only replay."""

from copy import deepcopy
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.evaluation.vietnamese_source_validation import (
    DATASET_PATH, EMBEDDING_IDENTITY, OWNER_REVIEW_PATH, registration_manifest,
    verify_owner_review, verify_registration,
)
from app.evaluation.chunking_comparison import BookLabel, ComparisonDataset, EvidenceLabel, QuestionLabel
from app.ingestion.parsers.base import ParsedDocument
from scripts.benchmark_book_chunking import CachedGemini, build_report
from scripts.benchmark_vietnamese_embeddings import (
    BoundedProvider, build_embedding_report, create_real_provider, main, markdown_summary,
)


def test_review_is_separate_and_hash_bound():
    review = verify_owner_review(verify_registration())
    assert review["decision"] == "approved" and review["questionCount"] == 20
    assert review["evidenceAnchorCount"] == 23 and review["sectionCount"] == 7
    assert not review["providerCostsAuthorizedByReview"] and not review["independentBlindExpertReview"]
    assert registration_manifest()["humanReview"] == "pending"


@pytest.mark.parametrize("field,value", [
    ("decision", "pending"), ("dataset_file_sha256", "wrong"), ("source_pdf_sha256", "wrong"),
    ("approved_question_ids", []), ("approved_evidence_anchor_count", 22), ("approved_section_pages", []),
    ("independent_blind_expert_review_claim", True), ("provider_calls_authorized_by_this_receipt", True),
    ("historical_report_changes", True), ("recorded_at_utc", "2026-10-06T12:00:00+07:00"),
])
def test_incomplete_or_changed_review_blocks_run(tmp_path, field, value):
    review = json.loads(OWNER_REVIEW_PATH.read_text(encoding="utf-8"))
    review[field] = value
    path = tmp_path / "review.json"
    path.write_text(json.dumps(review), encoding="utf-8")
    with pytest.raises(ValueError):
        verify_owner_review(verify_registration(), path)


@pytest.mark.asyncio
async def test_budget_counts_failed_attempt_and_rejects_repeat_or_unregistered_text():
    provider = SimpleNamespace(embed=AsyncMock(side_effect=RuntimeError("DO_NOT_EXPORT_KEY")),
                               embed_query=AsyncMock(return_value=[1, 0]))
    bounded = BoundedProvider(provider, allowed_inputs={("document", "a"), ("query", "q")}, max_inputs=1)
    with pytest.raises(RuntimeError):
        await bounded.embed(["a"])
    assert bounded.counters()["submittedInputCount"] == 1
    assert bounded.counters()["successfulResponseInputCount"] == 0
    for call in (bounded.embed(["a"]), bounded.embed(["unregistered"]), bounded.embed_query("q")):
        with pytest.raises(ValueError):
            await call
    assert provider.embed.await_count == 1 and provider.embed_query.await_count == 0
    assert bounded.request_count == 1 and "DO_NOT_EXPORT" not in json.dumps(bounded.counters())


@pytest.mark.asyncio
async def test_cache_reuses_task_text_and_reserves_only_real_missing_inputs(tmp_path):
    provider = SimpleNamespace(embed=AsyncMock(return_value=[[1, 0]]), embed_query=AsyncMock(return_value=[0, 1]))
    bounded = BoundedProvider(provider, allowed_inputs={("document", "same"), ("query", "same")}, max_inputs=2)
    settings = EMBEDDING_IDENTITY.settings().model_copy(update={"embedding_dim": 2})
    cache = CachedGemini(bounded, cache_dir=tmp_path, settings=settings, interval=0)
    assert await cache.embed_documents(["same", "same"]) == [[1, 0], [1, 0]]
    assert await cache.embed_query("same") == [0, 1]
    assert await cache.embed_documents(["same"]) == [[1, 0]]
    assert bounded.counters()["byTaskSubmitted"] == {"document": 1, "query": 1}
    assert bounded.request_count == 2
    replay = CachedGemini(BoundedProvider(None, allowed_inputs=set(), max_inputs=0),
                          cache_dir=tmp_path, settings=settings, interval=0)
    assert await replay.embed_query("same") == [0, 1]
    assert await replay.embed_documents(["same"]) == [[1, 0]]


def test_real_factory_pins_identity_and_disables_both_retry_layers(monkeypatch):
    from google import genai
    client_calls = []
    client = SimpleNamespace(close=lambda: None)
    monkeypatch.setattr("app.core.config.get_settings", lambda: SimpleNamespace(gemini_api_key="DO_NOT_EXPORT_KEY"))
    monkeypatch.setattr(genai, "Client", lambda **kwargs: client_calls.append(kwargs) or client)
    provider, actual_client = create_real_provider(EMBEDDING_IDENTITY.settings())
    assert actual_client is client and provider.model_name == "gemini-embedding-2"
    assert provider.dimension == 3072 and provider.max_retries == 0 and provider.batch_size == 8
    assert client_calls[0]["http_options"].retry_options.attempts == 1
    assert client_calls[0]["http_options"].timeout == 30000 and client_calls[0]["vertexai"] is False


@pytest.mark.asyncio
@pytest.mark.parametrize("fail_provider", [False, True])
async def test_generic_injection_uses_pinned_identity_and_keeps_failed_call_counters(tmp_path, monkeypatch, fail_provider):
    monkeypatch.setattr("scripts.benchmark_book_chunking.get_settings", lambda: pytest.fail("No .env on pinned path"))
    monkeypatch.setattr("scripts.benchmark_book_chunking.parse_pdf", lambda *args, **kwargs: [
        ParsedDocument("The brass key is under the old door.", {"page_number": 1})])
    labeled = BookLabel(id="unit-only", title="Unit", filename="unit.pdf", sha256="a" * 64,
                       source_url="https://example.org/unit.pdf", rights_url="https://example.org/rights",
                       questions=[QuestionLabel(id="q", question="Where is the key?", evidence=[
                           EvidenceLabel(page=1, quote="The brass key is under the old door.")])])
    provider = SimpleNamespace(embed=AsyncMock(return_value=[[1, 0]]), embed_query=AsyncMock(return_value=[1, 0]))
    if fail_provider:
        provider.embed.side_effect = RuntimeError("DO_NOT_EXPORT_KEY 429 quota")
    pinned = EMBEDDING_IDENTITY.settings().model_copy(update={"embedding_dim": 2})
    report = await build_report(ComparisonDataset(name="unit", version="1", books=[labeled]), tmp_path,
                                gemini=True, interval=0, embedding_settings=pinned,
                                provider_factory=lambda **_: provider)
    assert report["embedding"]["dimension"] == 2
    assert report["embedding"]["requestCount"] >= 1
    assert report["decision"]["gates"]["denseHybridCompleted"] is (not fail_provider)
    if fail_provider:
        assert report["embedding"]["errorCategory"] == "rate_or_quota"
        assert report["errorCount"] == 1 and report["embedding"]["requestCount"] == 1
    assert "DO_NOT_EXPORT" not in json.dumps(report)


def stub_report():
    retrieval = {mode: {"caseCount": 20, "metricsAtK": {"3": {"evidenceRecall": .5},
                  "5": {"reciprocalRank": .5}}, "contextRetainedAnchorRate": .5}
                 for mode in ("bm25", "dense", "hybrid")}
    return {"generatedAt": "unit-test-only", "config": {}, "books": [{"id": "test", "versions": {
        version: {"chunkCount": 1, "nonWhitespaceSourceCoverage": 1,
                  "chapterAudit": {"chapterLabelMismatchCount": 1}, "retrieval": deepcopy(retrieval)}
        for version in ("v1", "v2")}}], "errorCount": 0,
        "embedding": {"status": "passed", "uniqueInputCount": 1},
        "decision": {"gates": {}, "recommendation": "old"}, "limitations": [],
        "runtimeWrites": False, "llmGeneration": False}


@pytest.mark.asyncio
async def test_review_gate_precedes_preflight_or_provider(tmp_path, monkeypatch):
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.verify_owner_review",
                        lambda *_: (_ for _ in ()).throw(ValueError("review pending")))
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.collect_embedding_inputs",
                        lambda *_: pytest.fail("Review must be checked first"))
    with pytest.raises(ValueError, match="review pending"):
        await build_embedding_report(tmp_path, gemini=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("cap,interval", [(221, 2), (0, 2), (True, 2), (220, float("nan")), (220, -1)])
async def test_bad_cap_or_pacing_blocks_even_input_read(tmp_path, monkeypatch, cap, interval):
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.verify_registration", lambda *_: pytest.fail("No input read"))
    with pytest.raises(ValueError):
        await build_embedding_report(tmp_path, gemini=True, max_inputs=cap, interval=interval)


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["too_many_inputs", "missing_cache", "corrupt_cache"])
async def test_preflight_errors_never_construct_provider(tmp_path, monkeypatch, failure):
    inputs = {("query", str(i)) for i in range(221 if failure == "too_many_inputs" else 1)}
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.collect_embedding_inputs", lambda *_: inputs)
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.create_real_provider", lambda *_: pytest.fail("No paid I/O"))
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.build_report", lambda *_: pytest.fail("No scoring"))
    if failure == "corrupt_cache":
        cache = CachedGemini(None, cache_dir=tmp_path / "cache", settings=EMBEDDING_IDENTITY.settings(), interval=0)
        cache.cache_dir.mkdir()
        (cache.cache_dir / f"embedding-{cache.key('query', '0')}.json").write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError):
        await build_embedding_report(tmp_path, gemini=failure == "too_many_inputs")


@pytest.mark.asyncio
@pytest.mark.parametrize("submission_cap", [119, 120])
async def test_resume_budget_applies_to_missing_not_total_inputs(tmp_path, monkeypatch, submission_cap):
    inputs = {("query", str(i)) for i in range(216)}  # Synthetic task/text pairs only.
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.collect_embedding_inputs", lambda *_: inputs)
    monkeypatch.setattr(CachedGemini, "read", lambda self, task, text: [1.] * 3072 if int(text) < 96 else None)
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.create_real_provider", lambda *_: pytest.fail("No paid I/O"))
    build = AsyncMock(return_value=stub_report())
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.build_report", build)
    if submission_cap == 119:
        with pytest.raises(ValueError, match="submission budget"):
            await build_embedding_report(tmp_path, gemini=True, max_inputs=submission_cap)
        build.assert_not_awaited()
    else:
        report = await build_embedding_report(tmp_path, gemini=True, max_inputs=submission_cap)
        assert report["providerAuthorization"]["maxSubmittedInputs"] == 120
        assert build.call_args.kwargs["max_embedding_texts"] == 220  # Separate immutable corpus bound.
        assert report["errorCount"] == 1  # Stub did not fill the real cache; not a quality pass.


@pytest.mark.asyncio
async def test_cache_only_full_report_requires_no_env_and_keeps_historical_registration(tmp_path, monkeypatch):
    inputs = {("query", "q")}
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.collect_embedding_inputs", lambda *_: inputs)
    monkeypatch.setattr("app.core.config.get_settings", lambda *_: pytest.fail("No .env"))
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.create_real_provider", lambda *_: pytest.fail("No provider"))
    cache = CachedGemini(None, cache_dir=tmp_path / "cache", settings=EMBEDDING_IDENTITY.settings(), interval=0)
    cache.save("query", "q", [1.] * 3072)
    build = AsyncMock(return_value=stub_report())
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.build_report", build)
    report = await build_embedding_report(tmp_path)
    bounded = build.call_args.kwargs["provider_factory"](settings=EMBEDDING_IDENTITY.settings())
    assert bounded.provider is None
    assert report["caseCount"] == 120 and report["providerCalls"] == 0
    assert report["caseCountByMode"] == {"bm25": 40, "dense": 40, "hybrid": 40}
    assert report["ownerReview"]["decision"] == "approved"
    assert report["firstScoringRegistration"]["humanReview"] == "pending"
    assert report["decision"]["gates"]["denseHybridCompleted"]
    assert report["decision"]["gates"]["humanReviewCompleted"]
    assert not report["decision"]["gates"]["independentBlindExpertReview"]
    assert not report["automaticPromotion"] and not report["runtimeWrites"] and not report["llmGeneration"]
    assert "HOLD v1" in markdown_summary(report)


@pytest.mark.asyncio
async def test_registration_and_review_rechecked_after_scoring(tmp_path, monkeypatch):
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.collect_embedding_inputs", lambda *_: set())
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.build_report", AsyncMock(return_value=stub_report()))
    original = verify_owner_review(verify_registration())
    calls = iter([original, original | {"receiptSha256": "changed"}])
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.verify_owner_review", lambda *_: next(calls))
    with pytest.raises(ValueError, match="changed during scoring"):
        await build_embedding_report(tmp_path)


@pytest.mark.parametrize("suffix", [".json", ".md"])
def test_cli_refuses_clobber_before_api(tmp_path, monkeypatch, suffix):
    output = tmp_path / "report.json"
    output.with_suffix(suffix).write_text("keep")
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.build_embedding_report", lambda *_: pytest.fail("No API"))
    assert main(["--gemini", "--output", str(output)]) == 1
    assert output.with_suffix(suffix).read_text() == "keep"


def test_cli_requires_explicit_paid_or_cache_mode(tmp_path):
    with pytest.raises(SystemExit):
        main(["--output", str(tmp_path / "new.json")])


def test_cli_redacts_secrets_and_preserves_failed_state(tmp_path, monkeypatch, capsys):
    async def fail(*args, **kwargs):
        raise RuntimeError("DO_NOT_EXPORT_KEY")
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.build_embedding_report", fail)
    assert main(["--gemini", "--output", str(tmp_path / "new.json")]) == 1
    captured = capsys.readouterr()
    assert "DO_NOT_EXPORT" not in captured.out + captured.err
