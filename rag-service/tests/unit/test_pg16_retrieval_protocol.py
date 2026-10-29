"""No provider/service calls: enforce the production benchmark's narrow protocol."""

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.evaluation.corpus_binding import bind_corpus, evidence_group_coverage
from app.indexing.qdrant_store import deterministic_qdrant_point_id

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pg16_protocol", ROOT / "scripts/corpora/run_pg16_retrieval.py")
protocol = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(protocol)


def reference():
    return json.loads((ROOT / "tests/fixtures/corpora/postgresql16_concurrency_v1/ingestion-v1.json").read_text(encoding="utf-8"))


def document(ref):
    return {"documentId": 2, "bookId": 3343, "ebookId": 11, "status": "INDEXED",
            "sourceSha256": ref["sourceSha256"], "chunks": [
                {**{k: c[k] for k in ("index", "sha256", "chunkingVersion")},
                 "vectorId": f"unit-only-{c['index']}"} for c in ref["chunks"]]}


def binding():
    return SimpleNamespace(bind_corpus=bind_corpus, evidence_group_coverage=evidence_group_coverage)


def records(ref, doc, version="unit-version"):
    return [SimpleNamespace(
        id=deterministic_qdrant_point_id(f"{c['vectorId']}:{version}"),
        payload={"vector_id": c["vectorId"], "document_id": doc["documentId"], "bookId": doc["bookId"],
                 "ebookId": doc["ebookId"], "source_checksum_sha256": ref["sourceSha256"],
                 "chunking_strategy_version": "v1", "embedding_version": version, "active": True,
                 "content": ref["chunks"][c["index"]]["text"],
                 "pageStart": ref["chunks"][c["index"]]["pageStart"],
                 "pageEnd": ref["chunks"][c["index"]]["pageEnd"]}) for c in doc["chunks"]]


def test_protocol_loads_only_dev_with_no_heldout_cases():
    ref = reference()
    doc, dataset, sidecar = protocol.select_dev(ref, [document(ref)], 2, protocol.load_binding())
    assert doc["documentId"] == 2
    assert len(dataset.items) == 30
    assert sum(item.answerable for item in dataset.items) == 27
    assert sidecar["split"] == "dev"
    assert {q.id for q in dataset.items}.isdisjoint({q["id"] for q in ref["labels"] if q["split"] == "heldout"})


@pytest.mark.parametrize("mutation", ["name", "source", "missing-document", "duplicate-document", "split-size"])
def test_wrong_reference_or_inventory_is_rejected(mutation):
    ref = reference()
    docs = [document(ref)]
    if mutation == "name": ref["corpus"] = "other"
    elif mutation == "source": ref["sourceSha256"] = "0" * 64
    elif mutation == "missing-document": docs = []
    elif mutation == "duplicate-document": docs.append(deepcopy(docs[0]))
    elif mutation == "split-size": ref["labels"].pop(0)
    with pytest.raises(ValueError):
        protocol.select_dev(ref, docs, 2, binding())


def test_preflight_reads_and_validates_all_expected_points():
    ref = reference()
    doc = document(ref)
    result = protocol.verify_point_payloads(doc, records(ref, doc), "unit-version", ref["pageCount"])
    assert result == {"expectedPointCount": 35, "verifiedPointCount": 35, "contentScopeVersionPageChecks": "PASS"}


@pytest.mark.parametrize("mutation", ["missing", "duplicate", "id", "scope", "source", "content", "version", "embedding", "inactive", "page", "page-type"])
def test_bad_point_cannot_pass_preflight(mutation):
    ref = reference()
    doc = document(ref)
    points = records(ref, doc)
    if mutation == "missing": points.pop()
    elif mutation == "duplicate": points.append(deepcopy(points[0]))
    elif mutation == "id": points[0].id = "wrong-id"
    elif mutation == "scope": points[0].payload["ebookId"] = 999
    elif mutation == "source": points[0].payload["source_checksum_sha256"] = "0" * 64
    elif mutation == "content": points[0].payload["content"] += "modified"
    elif mutation == "version": points[0].payload["chunking_strategy_version"] = "v2"
    elif mutation == "embedding": points[0].payload["embedding_version"] = "another-version"
    elif mutation == "inactive": points[0].payload["active"] = False
    elif mutation == "page": points[0].payload["pageStart"] = 0
    elif mutation == "page-type": points[0].payload["pageStart"] = True
    with pytest.raises(ValueError):
        protocol.verify_point_payloads(doc, points, "unit-version", ref["pageCount"])


@pytest.mark.asyncio
@pytest.mark.parametrize("fail_one", [False, True])
async def test_measure_runs_sixty_searches_but_never_answers(monkeypatch, fail_one):
    from app.evaluation import run_eval

    ref = reference()
    _, dataset, sidecar = protocol.select_dev(ref, [document(ref)], 2, binding())
    calls = []

    class SearchOnlyClient:
        def __init__(self, *, base_url, api_key):
            assert base_url == "http://127.0.0.1:8000"
            assert api_key == "unit-only-key"
        async def __aenter__(self): return self
        async def __aexit__(self, *_args): pass
        async def answer(self, *args, **kwargs):
            raise AssertionError("Generation must not be invoked.")
        async def search(self, item, *, mode, top_k):
            calls.append((item.id, mode, top_k))
            if fail_one and len(calls) == 1:
                raise RuntimeError("sensitive-provider-text-must-not-leak")
            hits = [{"vectorId": item.expected_chunk_ids[0],
                     "citation": {"bookId": 3343, "ebookId": 11, "documentInternalId": 2},
                     "metadata": {"chunking_strategy_version": "v1"}}] if item.answerable else []
            return {"results": hits, "embeddingVersion": "unit-version"}, 1.0

    monkeypatch.setattr(run_eval, "RagEvaluationClient", SearchOnlyClient)
    report, failures = await protocol.measure(dataset, sidecar, SimpleNamespace(rag_internal_api_key="unit-only-key"), binding())
    assert len(calls) == 60
    assert all(top_k == 3 for _, _, top_k in calls)
    assert all(mode == "dense" for _, mode, _ in calls[:30])
    assert all(mode == "hybrid" for _, mode, _ in calls[30:])
    assert "sensitive-provider-text" not in json.dumps(report)
    assert bool(failures) == fail_one
    assert report["operationGate"]["passed"] is not fail_one
    assert report["semanticReleaseGate"]["passed"] is None
    assert not any("answer" in row for mode in report["modes"].values() for row in mode["items"])
    assert "qualityGate" not in report


def test_cli_does_not_overwrite_an_existing_evidence_directory(monkeypatch, tmp_path, capsys):
    marker = tmp_path / "failure.json"
    marker.write_text("existing-evidence", encoding="utf-8")
    monkeypatch.setattr("sys.argv", ["run_pg16_retrieval.py", "--document-id", "2", "--output", str(tmp_path),
                                    "--helper-commit", "a" * 40,
                                    "--runtime-image", "ghcr.io/phandinhphuc1234/se330-rag@sha256:" + "b" * 64])
    assert protocol.main() == 1
    assert marker.read_text(encoding="utf-8") == "existing-evidence"
    assert "FileExistsError" in capsys.readouterr().out


def test_workflow_keeps_ci_and_ssh_guards_and_no_deploy():
    import yaml

    workflow = yaml.safe_load((ROOT.parent / ".github/workflows/rag-production-check.yml").read_text(encoding="utf-8"))
    job = workflow["jobs"]["pg16-retrieval-dev"]
    assert job["environment"] == "production"
    assert job["permissions"] == {"contents": "read", "actions": "read"}
    commands = [step.get("run", "") for step in job["steps"] if "run" in step]
    assert '"$ci_sha" == "$GITHUB_SHA"' in commands[0]
    script = commands[1]
    for required in ["StrictHostKeyChecking=yes", '"$actual_image" == "$4"', "run_pg16_retrieval.py", "ingestion-v1.json"]:
        assert required in script
    for forbidden in ["deploy.sh", "docker compose up", "StrictHostKeyChecking=no", "POST /", "--judge"]:
        assert forbidden not in script
    assert workflow["concurrency"]["group"] == "production-rag-verification"
