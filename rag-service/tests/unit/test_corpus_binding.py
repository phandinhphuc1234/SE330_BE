"""Technical benchmark binding is separate from the legacy six-chunk Magi golden."""

from collections import Counter
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

from app.evaluation.corpus_binding import bind_corpus, evidence_group_coverage

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests/fixtures/corpora/postgresql16_concurrency_v1"


def read(name):
    return json.loads((FIXTURE / name).read_text(encoding="utf-8"))


def indexed_document(reference):
    # Test-only inventory; these are never saved as a runnable production dataset.
    return {"documentId": 99, "bookId": 4000, "ebookId": 88,
            "status": "INDEXED", "sourceSha256": reference["sourceSha256"],
            "chunks": [{"index": c["index"], "sha256": c["sha256"], "chunkingVersion": c["chunkingVersion"],
                        "vectorId": f"test-only-vector-{c['index']}"} for c in reference["chunks"]]}


def test_splits_frozen_stratified_and_disjoint():
    specs = read("questions.json")
    items = specs["items"]
    assert len(items) == len({item["id"] for item in items}) == 40
    assert Counter((q["kind"], q["split"]) for q in items) == {
        ("factual", "dev"): 15, ("factual", "heldout"): 5,
        ("multi-section", "dev"): 8, ("multi-section", "heldout"): 2,
        ("sql-table", "dev"): 4, ("sql-table", "heldout"): 2,
        ("unanswerable", "dev"): 3, ("unanswerable", "heldout"): 1,
    }
    assert all("bookId" not in item and "documentId" not in item for item in items)
    assert all(bool(q["evidence"]) == (q["kind"] != "unanswerable") for q in items)


def test_source_hashes_and_complete_extraction_are_reproducible():
    manifest = read("source-manifest.json")
    for source in manifest["sources"]:
        assert hashlib.sha256((FIXTURE / source["file"]).read_bytes()).hexdigest() == source["sha256"]
    spec = importlib.util.spec_from_file_location("pg16_pdf_builder", ROOT / "scripts/corpora/build_postgresql16_pdf.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    extracted = module.extract_blocks(FIXTURE / "source")
    assert extracted == [{key: value for key, value in block.items() if key != "pages"} for block in manifest["blocks"]]
    assert Counter(block["kind"] for block in extracted)["table"] == 3
    license_text = " ".join(b["text"] for b in extracted if b["id"].startswith("legalnotice-"))
    for phrase in ["Copyright", "Permission to use, copy, modify, and distribute", "IN NO EVENT", "SPECIFICALLY DISCLAIMS"]:
        assert phrase in license_text


def test_offline_manifest_matches_specs_and_content_hashes():
    reference, manifest, specs = read("ingestion-v1.json"), read("source-manifest.json"), read("questions.json")
    assert reference["sourceSha256"] == manifest["pdfSha256"]
    assert reference["questionSpecSha256"] == hashlib.sha256((FIXTURE / "questions.json").read_bytes()).hexdigest()
    assert reference["anchorFailures"] == []
    assert reference["quality"]["status"] == "PASS"
    assert reference["chunkCount"] == len(reference["chunks"]) == 35
    for chunk in reference["chunks"]:
        assert chunk["sha256"] == hashlib.sha256(chunk["text"].encode()).hexdigest()
        assert chunk["chunkingVersion"] == "v1"
    for label, question in zip(reference["labels"], specs["items"], strict=True):
        for key in ("id", "question", "kind", "split", "answer", "points"):
            assert label[key] == question[key]
    assert reference["retrievalMeasured"] is reference["generationMeasured"] is False


@pytest.mark.parametrize("split,count", [("dev", 30), ("heldout", 10)])
def test_binding_preserves_questions_and_uses_verified_runtime_scope(split, count):
    reference = read("ingestion-v1.json")
    original = deepcopy(reference)
    dataset, sidecar = bind_corpus(reference, indexed_document(reference), split=split)
    assert reference == original
    assert len(dataset.items) == count
    assert all(item.book_id == 4000 and item.ebook_id == 88 and item.document_id == 99 for item in dataset.items)
    assert {item.id for item in dataset.items} == {q["id"] for q in reference["labels"] if q["split"] == split}
    assert all(split in item.tags for item in dataset.items)
    assert sidecar["status"] == "bound-not-evaluated"
    assert all(bool(item.expected_chunk_ids) == item.answerable for item in dataset.items)


@pytest.mark.parametrize("mutation", ["pdf", "status", "count", "content", "version", "vector", "duplicate-vector",
                                     "scope", "boolean-scope", "index", "duplicate-index"])
def test_runtime_mismatch_cannot_bind(mutation):
    reference = read("ingestion-v1.json")
    doc = indexed_document(reference)
    if mutation == "pdf": doc["sourceSha256"] = "0" * 64
    elif mutation == "status": doc["status"] = "PROCESSING"
    elif mutation == "count": doc["chunks"].pop()
    elif mutation == "content": doc["chunks"][0]["sha256"] = "0" * 64
    elif mutation == "version": doc["chunks"][0]["chunkingVersion"] = "v2"
    elif mutation == "vector": doc["chunks"][0]["vectorId"] = ""
    elif mutation == "duplicate-vector": doc["chunks"][1]["vectorId"] = doc["chunks"][0]["vectorId"]
    elif mutation == "scope": doc["ebookId"] = None
    elif mutation == "boolean-scope": doc["bookId"] = True
    elif mutation == "index": doc["chunks"][0]["index"] = 1000
    elif mutation == "duplicate-index": doc["chunks"][1]["index"] = 0
    with pytest.raises(ValueError):
        bind_corpus(reference, doc, split="dev")


@pytest.mark.parametrize("mutation", ["mode", "anchors", "indices", "hash", "page", "negative", "duplicate-question", "split"])
def test_reference_mismatch_cannot_bind(mutation):
    ref = read("ingestion-v1.json")
    doc = indexed_document(ref)
    split = "dev"
    if mutation == "mode": ref["mode"] = "unknown"
    elif mutation == "anchors": ref["anchorFailures"] = [{"id": "pg-f01"}]
    elif mutation == "indices": ref["labels"][0]["expectedChunkIndices"] = [0]
    elif mutation == "hash": ref["labels"][0]["evidence"][0]["chunks"][0]["sha256"] = "f" * 64
    elif mutation == "page": ref["labels"][0]["expectedPages"] = [999]
    elif mutation == "negative": ref["labels"][0]["kind"] = "unanswerable"
    elif mutation == "duplicate-question": ref["labels"][1]["id"] = ref["labels"][0]["id"]
    elif mutation == "split": split = "all"
    with pytest.raises(ValueError):
        bind_corpus(ref, doc, split=split)


def test_overlap_alternatives_do_not_double_count_evidence_group():
    groups = [["overlap-a", "overlap-b"], ["second-passage"]]
    assert evidence_group_coverage(groups, ["overlap-b", "second-passage"]) == 1
    assert evidence_group_coverage(groups, ["overlap-a", "overlap-b"]) == .5
    assert evidence_group_coverage(groups, []) == 0
    assert evidence_group_coverage([], ["unrelated"]) is None
    with pytest.raises(ValueError):
        evidence_group_coverage([[]], [])
