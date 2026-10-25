"""Bounded read-only production inspection and checksum-verified golden evaluation.

Never imports data, changes entitlement, reindexes, or exposes provider credentials.
Generation is opt-in and uses the entire unchanged 20-question reference dataset.
"""

import argparse
import asyncio
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

from app.evaluation.dataset import load_evaluation_dataset
from app.evaluation.run_eval import DEFAULT_DATASET, RagEvaluationClient, evaluate_dataset, quality_gate_failures, write_reports

SOURCE_SHA256 = "cd4206e9dfc52aabb655b27e3935da81b18f244209e30f8304b249f9156b96c9"
CHUNK_SHA256 = (
    "66a6bcec62ecad6f038f41c599d53fe91e58cb61ae79c270cd58b5b379f1dc2d",
    "a151810c5f32a72e996d6d4472421ebaac984ee3ea7ba13faef520bafd1000df",
    "6a266139d37af54ed6e17d3a8c256c44c1c75cd6721bb1529d1716c0c71d7c95",
    "17c98c4ff9dffeecc0e8749a6d2021f38dded45e3cd133985bc431f063c5d894",
    "8b84a70fff8f8ca8bb72070a4aca67c5f42fb3b3d21489f5271106dccba3cdf1",
    "53eecbd6d3a50bbca5ac0bce4400329848418a15ed59c1b9ee74058b08103f07",
)


def bind_reference(dataset, document):
    """Remap DB identifiers only after exact source AND ordered chunk hash matches."""
    if document["sourceSha256"] != SOURCE_SHA256 or document["status"] != "INDEXED":
        raise ValueError("Reference PDF checksum or indexed status mismatch.")
    chunks = sorted(document["chunks"], key=lambda row: row["index"])
    if len(chunks) != len(CHUNK_SHA256) or [row["index"] for row in chunks] != list(range(6)):
        raise ValueError("Reference requires exactly six ordered chunks.")
    identifiers = {}
    for index, (chunk, digest) in enumerate(zip(chunks, CHUNK_SHA256, strict=True)):
        if chunk["sha256"] != digest or chunk["chunkingVersion"] != "v1" or not chunk["vectorId"]:
            raise ValueError("Reference chunk content/version/vector mismatch.")
        identifiers[f"doc-1-chunk-{index}-{digest[:12]}"] = chunk["vectorId"]
    if not document["bookId"] or not document["ebookId"]:
        raise ValueError("Reference must be linked to a Library book and ebook.")
    items = [item.model_copy(update={
        "book_id": document["bookId"], "ebook_id": document["ebookId"],
        "document_id": document["documentId"],
        "expected_chunk_ids": [identifiers[key] for key in item.expected_chunk_ids],
    }) for item in dataset.items]
    return dataset.model_copy(update={"items": items})


def latency_summary(values):
    ordered = sorted(value for value in values if isinstance(value, (int, float)) and math.isfinite(value))
    if not ordered:
        return {"count": 0, "p50Ms": None, "p95Ms": None}
    # Nearest-rank percentiles; observational sample, not a load benchmark.
    return {"count": len(ordered), "p50Ms": ordered[math.ceil(.50 * len(ordered)) - 1],
            "p95Ms": ordered[math.ceil(.95 * len(ordered)) - 1]}


async def inventory():
    from sqlalchemy import select
    from app.db.session import async_session_factory
    from app.documents.models import Document, DocumentChunk
    from app.ingestion.models import IngestionJob

    result = []
    async with async_session_factory() as session:
        documents = (await session.scalars(select(Document).order_by(Document.id).limit(51))).all()
        if len(documents) > 50:
            raise ValueError("Inventory exceeds the bounded 50-document limit.")
        for doc in documents:
            chunks = (await session.scalars(select(DocumentChunk).where(
                DocumentChunk.document_id == doc.id).order_by(DocumentChunk.chunk_index).limit(1001))).all()
            if len(chunks) > 1000:
                raise ValueError("Inventory exceeds the bounded chunk limit.")
            job = await session.scalar(select(IngestionJob).where(
                IngestionJob.document_id == doc.id).order_by(IngestionJob.id.desc()).limit(1))
            result.append({
                "documentId": doc.id, "bookId": doc.book_id, "ebookId": doc.ebook_id,
                "sourceSha256": doc.metadata_.get("source_checksum_sha256"),
                "status": job.status if job else None, "jobId": job.id if job else None,
                "chunks": [{"index": chunk.chunk_index, "vectorId": chunk.vector_id,
                            "sha256": hashlib.sha256(chunk.content.encode()).hexdigest(),
                            "chunkingVersion": chunk.metadata_.get("chunking_strategy_version")}
                           for chunk in chunks],
            })
    return result


async def run(args):
    from app.core.config import get_settings
    from app.generation.prompt_builder import PROMPT_VERSION

    settings = get_settings()
    output = Path(args.output_dir)
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    documents = await inventory()
    snapshot = {"environment": settings.app_env, "generatedAt": datetime.now(timezone.utc).isoformat(),
                "mode": args.mode, "promptVersion": PROMPT_VERSION,
                "embeddingVersion": settings.embedding_version, "documents": documents}
    (output / "inventory.json").write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
    if args.mode == "inventory":
        print(json.dumps({"documentCount": len(documents), "outputDir": str(output)}))
        return 0
    if args.document_id <= 0:
        raise ValueError("Evaluation requires a positive document ID.")
    if PROMPT_VERSION != "library-ebook-answer-v2":
        raise ValueError("Deploy the approved prompt-v2 candidate before evaluation.")
    matches = [doc for doc in documents if doc["documentId"] == args.document_id]
    if len(matches) != 1:
        raise ValueError("Selected document is absent from inventory.")
    dataset = bind_reference(load_evaluation_dataset(DEFAULT_DATASET), matches[0])
    (output / "bound-dataset.json").write_text(dataset.model_dump_json(by_alias=True, indent=2), encoding="utf-8")
    async with RagEvaluationClient(base_url="http://127.0.0.1:8000", api_key=settings.rag_internal_api_key) as client:
        retrieval = await evaluate_dataset(dataset, client=client, modes=["dense", "hybrid"], top_k=3,
                                           evaluate_answers=False)
        failures = quality_gate_failures(retrieval, min_hit_rate=.94, min_recall=.91, gate_modes=["hybrid"])
        retrieval["qualityGate"] = {"passed": not failures, "failures": failures}
        retrieval["runtime"] = {"environment": settings.app_env, "sourceSha256": SOURCE_SHA256,
                                "documentId": args.document_id, "kind": "retrieval-comparison"}
        write_reports(retrieval, output / "retrieval")
        if failures:
            print("Retrieval gate failed; answer calls were not started.")
            return 1
        answers = await evaluate_dataset(dataset, client=client, modes=["hybrid"], top_k=3,
                                         evaluate_answers=True, llm_interval_seconds=30)
        failures = quality_gate_failures(answers, min_hit_rate=.94, min_recall=.91,
                                         require_correct_abstention=True)
        answers["qualityGate"] = {"passed": not failures, "failures": failures}
        answers["runtime"] = {"environment": settings.app_env, "sourceSha256": SOURCE_SHA256,
                              "documentId": args.document_id, "kind": "full-answer-evaluation"}
        rows = answers["modes"]["hybrid"]["items"]
        answers["observedLatency"] = {
            "retrieval": latency_summary([row.get("retrieval", {}).get("latencyMs") for row in rows]),
            "askIncludingRetrievalAndGeneration": latency_summary([
                row.get("answer", {}).get("metrics", {}).get("latencyMs") for row in rows]),
            "limitation": "20 sequential cases; not concurrent load, SLA, or model-only latency.",
        }
        write_reports(answers, output / "answers")
        print(json.dumps({"qualityGate": answers["qualityGate"], "summary": answers["modes"]["hybrid"]["summary"],
                          "observedLatency": answers["observedLatency"]}))
        return int(bool(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["inventory", "evaluate"], default="inventory")
    parser.add_argument("--document-id", type=int, default=0)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    try:
        return asyncio.run(run(args))
    except Exception as error:
        # Never include SDK response text, credentials or signed URLs in CI logs.
        print(f"Verification failed: {type(error).__name__}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
