"""One bounded, dev-only retrieval baseline against the existing production RAG.

Runs from a temporary benchmark bundle inside the deployed container. The helper
does not replace installed app files, deploy, upload/reindex, alter entitlements,
or generate answers. Normal search caches/metrics and embedding quota may change.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.util
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from statistics import fmean

# In production, cwd is the installed /app, NOT the temporary helper bundle.
sys.path.insert(0, str(Path.cwd()))
BUNDLE_ROOT = Path(__file__).resolve().parents[2]
CORPUS = "postgresql16_concurrency_v1"
PDF_SHA256 = "73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26"


def load_binding():
    spec = importlib.util.spec_from_file_location("pg16_benchmark_binding", BUNDLE_ROOT / "app/evaluation/corpus_binding.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def select_dev(reference, documents, document_id, binding):
    if reference.get("corpus") != CORPUS or reference.get("sourceSha256") != PDF_SHA256:
        raise ValueError("Use the reviewed PostgreSQL v1 reference, not a different corpus.")
    matches = [doc for doc in documents if doc.get("documentId") == document_id]
    if len(matches) != 1:
        raise ValueError("Production inventory must contain the selected document once.")
    document = matches[0]
    dataset, sidecar = binding.bind_corpus(reference, document, split="dev")
    if len(dataset.items) != 30 or sum(item.answerable for item in dataset.items) != 27:
        raise ValueError("The frozen dev split requires 30 cases, 27 answerable.")
    return document, dataset, sidecar


def verify_point_payloads(document, records, embedding_version, page_count):
    """Check all expected point contents/scope without creating collection/indexes."""
    from app.indexing.qdrant_store import deterministic_qdrant_point_id

    expected = {deterministic_qdrant_point_id(f"{chunk['vectorId']}:{embedding_version}"): chunk
                for chunk in document["chunks"]}
    actual = {str(record.id): record for record in records}
    if len(records) != len(actual) or set(actual) != set(expected):
        raise ValueError("Qdrant is missing, duplicating or substituting reference points.")
    for point_id, chunk in expected.items():
        payload = actual[point_id].payload or {}
        required = {"vector_id": chunk["vectorId"], "document_id": document["documentId"],
                    "bookId": document["bookId"], "ebookId": document["ebookId"],
                    "source_checksum_sha256": PDF_SHA256, "chunking_strategy_version": "v1",
                    "embedding_version": embedding_version, "active": True}
        if any(type(payload.get(key)) is not type(value) or payload.get(key) != value for key, value in required.items()):
            raise ValueError("Qdrant point scope/source/version differs from the DB reference.")
        if hashlib.sha256(str(payload.get("content", "")).encode()).hexdigest() != chunk["sha256"]:
            raise ValueError("Qdrant point content differs from the DB chunk.")
        first, last = payload.get("pageStart"), payload.get("pageEnd")
        if type(first) is not int or type(last) is not int or not 1 <= first <= last <= page_count:
            raise ValueError("Qdrant reference point has invalid page provenance.")
    return {"expectedPointCount": len(expected), "verifiedPointCount": len(actual),
            "contentScopeVersionPageChecks": "PASS"}


async def inspect_qdrant(settings, document, page_count):
    from qdrant_client import AsyncQdrantClient
    from app.indexing.qdrant_store import deterministic_qdrant_point_id

    client = AsyncQdrantClient(url=settings.qdrant_url, api_key=settings.qdrant_api_key, timeout=20)
    try:
        records = await client.retrieve(
            collection_name=settings.qdrant_collection_name,
            ids=[deterministic_qdrant_point_id(f"{c['vectorId']}:{settings.embedding_version}") for c in document["chunks"]],
            with_payload=True, with_vectors=False,
        )
        return verify_point_payloads(document, records, settings.embedding_version, page_count)
    finally:
        await client.close()


def attach_evidence_metrics(report, sidecar, binding):
    from app.evaluation.production_verification import latency_summary

    for data in report["modes"].values():
        coverages = []
        for row in data["items"]:
            groups = sidecar["evidenceGroups"][row["id"]]
            coverage = binding.evidence_group_coverage(groups, row.get("retrievedChunkIds", []))
            if coverage is not None:
                # Missing/error cases remain in the denominator as zero.
                row["evidenceGroupCoverage@3"] = coverage
                coverages.append(coverage)
        data["summary"]["evidenceGroupCoverage@3"] = fmean(coverages) if coverages else None
        data["observedLatency"] = latency_summary([
            row.get("retrieval", {}).get("latencyMs") for row in data["items"]])


async def measure(dataset, sidecar, settings, binding):
    from app.evaluation.run_eval import RagEvaluationClient, evaluate_dataset, quality_gate_failures

    async with RagEvaluationClient(base_url="http://127.0.0.1:8000", api_key=settings.rag_internal_api_key) as client:
        report = await asyncio.wait_for(evaluate_dataset(
            dataset, client=client, modes=["dense", "hybrid"], top_k=3, evaluate_answers=False,
        ), timeout=600)
    if any("answer" in row for mode in report["modes"].values() for row in mode["items"]):
        raise ValueError("Retrieval-only protocol must never contain generated answers.")
    attach_evidence_metrics(report, sidecar, binding)
    failures = quality_gate_failures(report)
    report["operationGate"] = {"passed": not failures, "failures": failures}
    report["semanticReleaseGate"] = {"passed": None, "reason": "Baseline collection only; no semantic release threshold is declared."}
    report["limitations"].extend([
        "Dev only: 30 English cases, 27 answerable and 3 unanswerable. Held-out not run.",
        "No generated answers; no abstention accuracy, answer faithfulness, or end-to-end reader claim.",
        "Dense runs before hybrid, 30 sequential cases each. Cache warmness can affect latency; not a controlled load test or SLA.",
        "Evidence-group coverage counts alternatives once; existing chunk recall still counts their union. These are different metrics.",
        "Search may populate normal caches/counters and consumes embedding quota; no ingestion or entitlement mutation is requested.",
    ])
    return report, failures


async def run(args):
    from app.core.config import get_settings
    from app.evaluation.production_verification import inventory
    from app.evaluation.run_eval import write_reports

    args.stage = "validate-input"
    if args.document_id <= 0 or not re.fullmatch(r"[a-f0-9]{40}", args.helper_commit):
        raise ValueError("A positive production document ID and exact helper commit are required.")
    if not re.fullmatch(r"ghcr\.io/phandinhphuc1234/se330-rag@sha256:[a-f0-9]{64}", args.runtime_image):
        raise ValueError("Use an immutable deployed RAG image digest.")
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    args.output_created = True
    settings = get_settings()
    if settings.app_env != "production" or not settings.rag_internal_api_key:
        raise ValueError("Benchmark must run inside the authorized production RAG runtime.")
    reference_path = BUNDLE_ROOT / "tests/fixtures/corpora/postgresql16_concurrency_v1/ingestion-v1.json"
    reference = json.loads(reference_path.read_text(encoding="utf-8"))
    binding = load_binding()
    args.stage = "inventory-and-binding"
    documents = await inventory()
    document, dataset, sidecar = select_dev(reference, documents, args.document_id, binding)
    metadata = {"generatedAt": datetime.now(timezone.utc).isoformat(), "environment": settings.app_env,
                "helperCommit": args.helper_commit, "runtimeImage": args.runtime_image,
                "embeddingVersion": settings.embedding_version, "sourceSha256": PDF_SHA256,
                "mode": "pg16-retrieval-dev", "generationEnabled": False, "heldoutRun": False,
                "documents": [document]}
    (args.output / "inventory.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    (args.output / "bound-dev.json").write_text(dataset.model_dump_json(by_alias=True, indent=2), encoding="utf-8")
    (args.output / "evidence-groups.json").write_text(json.dumps(sidecar, indent=2), encoding="utf-8")
    args.stage = "qdrant-preflight"
    points = await inspect_qdrant(settings, document, reference["pageCount"])
    (args.output / "qdrant-preflight.json").write_text(json.dumps(points, indent=2), encoding="utf-8")
    args.stage = "retrieval"
    report, failures = await measure(dataset, sidecar, settings, binding)
    report["runtime"] = {key: value for key, value in metadata.items() if key != "documents"}
    write_reports(report, args.output)
    print(json.dumps({"operationGate": report["operationGate"], "semanticReleaseGate": report["semanticReleaseGate"],
                      "summaries": {mode: data["summary"] for mode, data in report["modes"].items()}}))
    return int(bool(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--document-id", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--helper-commit", required=True)
    parser.add_argument("--runtime-image", required=True)
    args = parser.parse_args()
    try:
        return asyncio.run(run(args))
    except Exception as error:
        # Never echo SDK exception text, API keys, signed URLs or auth headers.
        safe = {"status": "FAILED", "errorType": type(error).__name__,
                "stage": getattr(args, "stage", "startup"), "generationEnabled": False}
        if getattr(args, "output_created", False):
            with (args.output / "failure.json").open("x", encoding="utf-8") as stream:
                json.dump(safe, stream, indent=2)
        print(json.dumps(safe))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
