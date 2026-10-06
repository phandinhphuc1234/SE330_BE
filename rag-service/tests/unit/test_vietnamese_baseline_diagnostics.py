"""Read-only diagnosis guards and complete-vs-partial source attribution."""

from copy import deepcopy
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.evaluation.chunking_comparison import AnchoredEvidence
from app.evaluation.vietnamese_source_validation import (
    EMBEDDING_IDENTITY, registration_manifest, verify_owner_review, verify_registration,
)
from scripts import diagnose_vietnamese_baseline as module


def reference():
    dataset = verify_registration()
    review = verify_owner_review(dataset)
    value = {"stage": "vietnamese-source-owner-reviewed-embedding-baseline-v1",
             "errorCount": 0, "caseCount": 120, "caseCountByMode": {"bm25": 40, "dense": 40, "hybrid": 40},
             "ownerReview": review, "firstScoringRegistration": registration_manifest(),
             "providerCalls": 0, "providerAuthorization": {"mode": "cache_only"},
             "runtimeWrites": False, "llmGeneration": False, "automaticPromotion": False,
             "embedding": EMBEDDING_IDENTITY.report() | {"status": "passed", "submittedInputCount": 0},
             "dataset": {"name": dataset.name, "version": dataset.version, "split": dataset.split,
                         "queryLanguage": dataset.query_language, "sourceLanguage": dataset.source_language,
                         "reviewStatus": dataset.review_status, "baselineDatasetSha256": dataset.baseline_dataset_sha256,
                         "manifestContentSha256": hashlib.sha256(json.dumps(
                             dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()},
             "config": module.expected_reference_config(),
             "books": [{"id": dataset.books[0].id, "versions": {"v1": {"unitOnly": True}, "v2": {"unitOnly": True}}}]}
    return dataset, review, value


@pytest.mark.parametrize("field,value", [
    ("errorCount", 1), ("caseCount", 40), ("providerCalls", 1),
    ("runtimeWrites", True), ("llmGeneration", True), ("automaticPromotion", True),
    ("ownerReview", {}), ("firstScoringRegistration", {}), ("caseCountByMode", {"bm25": 40}),
])
def test_incomplete_unreviewed_or_mutating_reference_fails(tmp_path, field, value):
    dataset, review, payload = reference()
    payload[field] = value
    path = tmp_path / "unit-reference.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError):
        module.load_reference(path, dataset, review)


@pytest.mark.parametrize("change", ["model", "dimension", "ranking", "reranker", "mode", "source", "dataset", "context"])
def test_identity_or_scope_drift_fails_closed(tmp_path, change):
    dataset, review, payload = reference()
    if change in {"model", "dimension"}:
        payload["embedding"][change] = "changed"
    elif change == "ranking":
        payload["config"]["ranking"]["hybrid_vector_weight"] = .8
    elif change == "reranker":
        payload["config"]["rerankerWeights"]["cosine"] = .8
    elif change == "mode":
        payload["providerAuthorization"]["mode"] = "explicit_gemini_opt_in"
    elif change == "dataset":
        payload["dataset"]["manifestContentSha256"] = "changed"
    elif change == "context":
        payload["config"]["contextSeedK"] = 4
    else:
        payload["books"][0]["id"] = "another-source"
    path = tmp_path / "unit-reference.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError):
        module.load_reference(path, dataset, review)


def test_reference_load_is_public_pinned_and_preserves_original_review(tmp_path, monkeypatch):
    dataset, review, payload = reference()
    path = tmp_path / "unit-reference.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    monkeypatch.setattr("app.core.config.get_settings", lambda: pytest.fail("No runtime config selection"))
    actual, digest = module.load_reference(path, dataset, review)
    assert actual == payload and len(digest) == 64
    assert actual["firstScoringRegistration"]["humanReview"] == "pending"
    assert actual["ownerReview"]["decision"] == "approved"


def test_headings_use_actual_detectors_not_reviewed_titles():
    text = "## Giới thiệu\nA paragraph.\n\n## Phần I. Tiến bộ\nAnother paragraph."
    labels = [SimpleNamespace(page=1, heading=heading, title=heading)
              for heading in ("Giới thiệu", "Phần I. Tiến bộ")]
    value = module.heading_diagnostics(SimpleNamespace(chapters=labels), {1: text}, {"mismatches": []})
    assert value["v1ReviewedHeadingsDetected"] == 0  # v1 does not unwrap Markdown
    assert value["v2ReviewedHeadingsDetected"] == 1  # unnumbered Vietnamese heading unsupported
    assert [row["markdownWrapper"] for row in value["reviewedHeadings"]] == [True, True]
    assert [row["nonemptyLineNumber"] for row in value["reviewedHeadings"]] == [1, 3]
    assert labels[0].title == "Giới thiệu"  # no oracle/label rewrites


def test_late_plain_numbered_heading_distinguishes_scan_window_from_pattern():
    text = "\n".join(["paragraph"] * 8 + ["Phần I. Tiến bộ", "text"])
    label = SimpleNamespace(page=1, heading="Phần I. Tiến bộ", title="Phần I. Tiến bộ")
    value = module.heading_diagnostics(SimpleNamespace(chapters=[label]), {1: text}, {"mismatches": []})
    row = value["reviewedHeadings"][0]
    assert row["nonemptyLineNumber"] == 9 and row["v1ScanLimit"] == 8
    assert not row["v1DetectedReviewedHeading"] and row["v2DetectedReviewedHeading"]


def test_anchor_split_does_not_become_complete_single_citation():
    anchor = AnchoredEvidence(page=1, quote="abcdefghij", positions=frozenset(range(10)))
    def chunk(key, index):
        return SimpleNamespace(text="unit only", metadata={"vector_id": key, "chunk_index": index,
                               "pageStart": 1, "pageEnd": 1, "chapter_title": "Part"})
    chunks = [chunk("a", 0), chunk("b", 1), chunk("c", 2)]
    ranges = {"a": [(1, 0, 7)], "b": [(1, 7, 10)], "c": [(1, 11, 20)]}
    dense = {key: {"rank": index + 1, "cosine": .8} for index, key in enumerate(ranges)}
    lexical = {"variant0": {key: {"rank": None, "bm25Score": 0.} for key in ranges}}
    cases = {"hybrid": {"seedCitations": [{"vectorId": "a"}], "contextChunkIds": ["b"]}}
    original = deepcopy((ranges, dense, lexical, cases))
    rows = module.anchor_chunk_rows(anchor, chunks, ranges, dense, lexical, cases)
    assert [row["anchorCoverageFraction"] for row in rows] == [.7, .3]
    assert not any(row["fullAnchorInThisChunk"] for row in rows)
    assert rows[0]["membership"]["hybrid"] == {"top3Seed": True, "returnedContextId": False}
    assert rows[1]["membership"]["hybrid"] == {"top3Seed": False, "returnedContextId": True}
    assert rows[0]["keyword"]["variant0"]["rank"] is None  # zero BM25 != tied last rank
    assert (ranges, dense, lexical, cases) == original


@pytest.mark.asyncio
@pytest.mark.parametrize("missing_task", ["document", "query"])
async def test_missing_cache_never_constructs_provider_or_scores(tmp_path, monkeypatch, missing_task):
    _dataset, _review, payload = reference()
    path = tmp_path / "unit-reference.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    chunk = SimpleNamespace(text="unit only", metadata={"vector_id": "unit"})
    result = SimpleNamespace(cleaned_documents=[SimpleNamespace(text="unit", metadata={"page_number": 1})], chunks=[chunk])
    monkeypatch.setattr(module, "parse_pdf", lambda *_a, **_k: [])
    monkeypatch.setattr(module, "build_chunk_result", lambda *_a, **_k: result)
    monkeypatch.setattr(module, "anchor_questions", lambda *_a: {})
    monkeypatch.setattr(module, "source_ranges", lambda *_a: [(1, 0, 4)])
    monkeypatch.setattr(module.CachedGemini, "read", lambda self, task, text: None if task == missing_task else [1.] * 3072)
    monkeypatch.setattr(module, "evaluate_version", AsyncMock(side_effect=AssertionError("No scoring")))
    monkeypatch.setattr("scripts.benchmark_vietnamese_embeddings.create_real_provider", lambda *_a: pytest.fail("No provider"))
    monkeypatch.setattr("app.core.config.get_settings", lambda: pytest.fail("No runtime config selection"))
    with pytest.raises(ValueError, match=f"{missing_task} cache missing"):
        await module.build_diagnostics(tmp_path, path)


@pytest.mark.asyncio
async def test_replayed_baseline_drift_blocks_diagnosis(tmp_path, monkeypatch):
    _dataset, _review, payload = reference()
    path = tmp_path / "unit-reference.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    chunk = SimpleNamespace(text="unit", metadata={"vector_id": "unit"})
    result = SimpleNamespace(cleaned_documents=[SimpleNamespace(text="unit", metadata={"page_number": 1})], chunks=[chunk])
    monkeypatch.setattr(module, "parse_pdf", lambda *_a, **_k: [])
    monkeypatch.setattr(module, "build_chunk_result", lambda *_a, **_k: result)
    monkeypatch.setattr(module, "anchor_questions", lambda *_a: {})
    monkeypatch.setattr(module, "source_ranges", lambda *_a: [(1, 0, 4)])
    monkeypatch.setattr(module.CachedGemini, "read", lambda *_a: [1.] * 3072)
    monkeypatch.setattr(module, "evaluate_version", AsyncMock(return_value={"changed": True}))
    monkeypatch.setattr(module, "heading_diagnostics", lambda *_a: pytest.fail("No diagnosis on drift"))
    with pytest.raises(ValueError, match="baseline replay drifted"):
        await module.build_diagnostics(tmp_path, path)


@pytest.mark.parametrize("suffix", [".json", ".md"])
def test_cli_no_overwrite_before_input_work(tmp_path, monkeypatch, suffix):
    output = tmp_path / "owned.json"
    output.with_suffix(suffix).write_text("user-owned", encoding="utf-8")
    monkeypatch.setattr(module, "build_diagnostics", lambda *_a: pytest.fail("No input work"))
    assert module.main(["--output", str(output)]) == 1
    assert output.with_suffix(suffix).read_text() == "user-owned"


def test_cli_redacts_raw_errors_and_writes_no_passing_report(tmp_path, monkeypatch, capsys):
    output = tmp_path / "new.json"
    monkeypatch.setattr(module, "build_diagnostics", AsyncMock(side_effect=RuntimeError("DO_NOT_EXPORT_KEY")))
    assert module.main(["--output", str(output)]) == 1
    text = capsys.readouterr()
    assert "DO_NOT_EXPORT" not in text.out + text.err
    assert not output.exists() and not output.with_suffix(".md").exists()
