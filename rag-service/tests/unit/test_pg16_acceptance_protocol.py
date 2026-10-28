from copy import deepcopy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from app.core.config import Settings
from app.evaluation.corpus_binding import bind_corpus, evidence_group_coverage

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pg16_acceptance", ROOT / "scripts/corpora/run_pg16_acceptance.py")
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)
BINDING = SimpleNamespace(bind_corpus=bind_corpus, evidence_group_coverage=evidence_group_coverage)
IMAGE = "ghcr.io/phandinhphuc1234/se330-rag@sha256:" + "a" * 64


def fixture():
    ref = json.loads((ROOT / "tests/fixtures/corpora/postgresql16_concurrency_v1/ingestion-v1.json").read_text())
    doc = {"documentId": 2, "bookId": 3343, "ebookId": 11, "status": "INDEXED",
           "sourceSha256": ref["sourceSha256"], "chunks": [
               {"index": c["index"], "sha256": c["sha256"], "chunkingVersion": "v1",
                "vectorId": f"unit-only-{c['index']}"} for c in ref["chunks"]]}
    return ref, doc


@pytest.mark.parametrize("split,counts", [("dev", (30, 27)), ("heldout", (10, 9))])
def test_full_split_is_exact_disjoint_and_frozen(split, counts):
    ref, doc = fixture()
    _, dataset, sidecar = acceptance.select_split(ref, [doc], 2, split, BINDING)
    assert (len(dataset.items), sum(item.answerable for item in dataset.items)) == counts
    assert sidecar["split"] == split
    assert {q.id for q in dataset.items} == {label["id"] for label in ref["labels"] if label["split"] == split}


@pytest.mark.parametrize("mutation", ["split", "sha", "heldout-count"])
def test_unreviewed_split_or_labels_are_rejected(mutation):
    ref, doc = fixture()
    split = "heldout"
    if mutation == "split": split = "subset"
    elif mutation == "sha": ref["questionSpecSha256"] = "0" * 64
    else: ref["labels"] = [item for item in ref["labels"] if item["id"] != "pg-u04"]
    with pytest.raises(ValueError):
        acceptance.select_split(ref, [doc], 2, split, BINDING)


def test_candidate_is_stable_sensitive_to_settings_labels_image_and_never_contains_keys():
    ref, _ = fixture()
    settings = Settings(_env_file=None, gemini_api_key="unit-only-secret-key")
    manifest, digest = acceptance.candidate_manifest(settings, IMAGE, "b" * 40, ref)
    assert "unit-only-secret-key" not in json.dumps(manifest)
    assert acceptance.candidate_manifest(settings, IMAGE, "b" * 40, ref)[1] == digest
    changed = settings.model_copy(update={"llm_temperature": .25})
    assert acceptance.candidate_manifest(changed, IMAGE, "b" * 40, ref)[1] != digest
    changed_ref = deepcopy(ref)
    changed_ref["labels"][0]["question"] += " changed"
    assert acceptance.candidate_manifest(settings, IMAGE, "b" * 40, changed_ref)[1] != digest
    assert acceptance.candidate_manifest(settings, IMAGE[:-1] + "c", "b" * 40, ref)[1] != digest


@pytest.mark.asyncio
@pytest.mark.parametrize("fail_one", [False, True])
async def test_heldout_uses_ten_full_hybrid_answers_no_judge_preserves_provider_failures(monkeypatch, tmp_path, fail_one):
    from app.evaluation import run_eval
    ref, doc = fixture()
    _, dataset, sidecar = acceptance.select_split(ref, [doc], 2, "heldout", BINDING)
    calls = []

    async def no_wait(self):
        assert self.interval_seconds == 30

    class FakeClient:
        def __init__(self, *, base_url, api_key):
            assert base_url == "http://127.0.0.1:8000" and api_key == "unit-only-key"
        async def __aenter__(self): return self
        async def __aexit__(self, *_): pass
        async def search(self, item, *, mode, top_k):
            assert mode == "hybrid" and top_k == 3
            hits = [{"vectorId": key, "citation": {"bookId": 3343, "ebookId": 11, "documentInternalId": 2},
                     "metadata": {"chunking_strategy_version": "v1"}} for key in item.expected_chunk_ids[:3]]
            return {"results": hits, "embeddingVersion": "unit-version"}, 1
        async def answer(self, item, *, mode, top_k):
            calls.append(item.id)
            if fail_one and len(calls) == 1:
                response = httpx.Response(429, json={"error_code": "LLM_RATE_LIMITED"},
                                          request=httpx.Request("POST", "http://internal.test/answers"))
                response.raise_for_status()
            citations = [{"chunkId": item.expected_chunk_ids[0], "bookId": 3343, "ebookId": 11,
                          "documentInternalId": 2, "pageStart": 2, "pageEnd": 2}] if item.answerable else []
            return {"answer": "unit answer" if item.answerable else "", "abstained": not item.answerable,
                    "grounded": item.answerable, "citations": citations, "promptVersion": "library-ebook-answer-v2"}, 2

    monkeypatch.setattr(run_eval, "RagEvaluationClient", FakeClient)
    monkeypatch.setattr(run_eval.LLMRequestPacer, "wait", no_wait)
    report, failures = await acceptance.measure(dataset, sidecar,
                                               SimpleNamespace(rag_internal_api_key="unit-only-key"), BINDING, tmp_path)
    assert calls == [item.id for item in dataset.items]
    assert len((tmp_path / "answer-attempts.jsonl").read_text().splitlines()) == 10
    assert report["dataset"]["itemCount"] == 10
    assert report["semanticCorrectnessGate"]["passed"] is None
    assert report["qualityGate"]["passed"] is not fail_one
    assert bool(failures) is fail_one
    if fail_one:
        assert report["modes"]["hybrid"]["items"][0]["errorCode"] == "LLM_RATE_LIMITED"
        assert report["modes"]["hybrid"]["summary"]["answer.evaluatedCount"] == 9


@pytest.mark.parametrize("citation", [{"pageStart": True}, {"pageStart": 23}, {"pageStart": 3, "pageEnd": 2}])
def test_contract_rejects_invalid_pdf_page_without_claiming_semantic_accuracy(citation):
    report = {"dataset": {"itemCount": 1}, "topK": 3, "modes": {"hybrid": {
        "summary": {"hitRate@3": 1, "recall@3": 1, "answer.evaluatedCount": 1},
        "items": [{"id": "unit", "answerable": True, "answer": {"abstained": False}}]}}}
    raw = {"unit": {"answer": "answer", "grounded": True, "abstained": False, "citations": [citation]}}
    assert acceptance.contract_failures(report, raw, 22) == ["unit:citationPageBounds"]


def test_workflow_keeps_explicit_plan_digest_ci_image_ssh_guards():
    import yaml
    workflow = yaml.safe_load((ROOT.parent / ".github/workflows/rag-production-check.yml").read_text())
    job = workflow["jobs"]["pg16-retrieval-dev"]
    scripts = "\n".join(step.get("run", "") for step in job["steps"])
    assert "run_pg16_acceptance.py" in scripts
    assert '"$actual_image" == "$4"' in scripts
    assert '"$ci_sha" == "$GITHUB_SHA"' in scripts
    assert '"$CANDIDATE_SHA" =~ ^[a-f0-9]{64}$' in scripts
    assert "${{ inputs." not in scripts
    assert "StrictHostKeyChecking=no" not in scripts
    assert "--judge" not in scripts and "docker compose up" not in scripts
