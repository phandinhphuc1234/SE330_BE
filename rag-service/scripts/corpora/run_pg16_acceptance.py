"""Explicit opt-in PostgreSQL answer evaluation on a frozen production candidate.

Plan is provider-free. Dev and heldout each run their full unchanged split once,
hybrid/topK=3, paced, without a judge or automatic rerun/stitching. Storage and
authorization are not modified. Partial attempts survive a timeout in JSONL.
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

sys.path.insert(0, str(Path.cwd()))
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pg16_retrieval_protocol", Path(__file__).with_name("run_pg16_retrieval.py"))
protocol = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(protocol)
QUESTION_SHA = "22930554efe7a7fa5a70a28683ad75c5fe6dd133991c647cedc78c99688addb2"
CONFIG_FIELDS = (
    "embedding_provider", "embedding_model", "embedding_dim", "embedding_version", "embedding_text_policy",
    "llm_provider", "llm_model", "llm_temperature", "llm_max_tokens", "llm_timeout_seconds",
    "gemini_thinking_budget", "retrieval_mode", "retrieval_top_k", "reranker_top_k",
    "hybrid_candidate_multiplier", "hybrid_rrf_k", "hybrid_vector_weight", "hybrid_keyword_weight",
    "hybrid_fail_open", "keyword_candidate_limit",
    "query_rewrite_max_queries", "context_expansion_window", "answer_retrieval_top_k",
    "answer_score_threshold", "answer_max_context_chars",
)


def select_split(reference, documents, document_id, split, binding):
    # Reuse the same full-corpus checks as the already-run retrieval protocol.
    document, _, _ = protocol.select_dev(reference, documents, document_id, binding)
    if reference.get("questionSpecSha256") != QUESTION_SHA or split not in ("dev", "heldout"):
        raise ValueError("Frozen question specification and explicit split required.")
    dataset, sidecar = binding.bind_corpus(reference, document, split=split)
    expected = (30, 27) if split == "dev" else (10, 9)
    if (len(dataset.items), sum(item.answerable for item in dataset.items)) != expected:
        raise ValueError("Frozen split size/answerability changed.")
    return document, dataset, sidecar


def candidate_manifest(settings, runtime_image, helper_commit, reference):
    from app.generation.prompt_builder import PROMPT_VERSION
    if PROMPT_VERSION != "library-ebook-answer-v2":
        raise ValueError("Approved prompt-v2 candidate is required.")
    manifest = {
        "runtimeImage": runtime_image, "helperCommit": helper_commit,
        "sourceSha256": protocol.PDF_SHA256, "questionSpecSha256": QUESTION_SHA,
        "referenceSha256": hashlib.sha256(json.dumps(reference, sort_keys=True).encode()).hexdigest(),
        "promptVersion": PROMPT_VERSION, "config": {name: getattr(settings, name) for name in CONFIG_FIELDS},
        "protocol": {"mode": "hybrid", "topK": 3, "llmIntervalSeconds": 30, "judge": False},
    }
    digest = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return manifest, digest


def contract_failures(report, raw_answers, page_count):
    from app.evaluation.run_eval import quality_gate_failures
    failures = quality_gate_failures(report, require_correct_abstention=True)
    for row in report["modes"]["hybrid"]["items"]:
        answer = raw_answers.get(row["id"])
        if answer is None:
            continue  # Incomplete/error cases already fail the contract gate.
        citations = answer.get("citations") or []
        if answer.get("abstained"):
            if answer.get("grounded") or citations:
                failures.append(f"{row['id']}:abstentionContract")
        elif not answer.get("grounded") or not citations or not str(answer.get("answer", "")).strip():
            failures.append(f"{row['id']}:groundedAnswerContract")
        for citation in citations:
            start, end = citation.get("pageStart"), citation.get("pageEnd")
            if end is None:
                end = start
            if type(start) is not int or type(end) is not int or not 1 <= start <= end <= page_count:
                failures.append(f"{row['id']}:citationPageBounds")
    return sorted(set(failures))


async def measure(dataset, sidecar, settings, binding, output):
    from app.evaluation.run_eval import RagEvaluationClient, evaluate_dataset
    from app.evaluation.production_verification import latency_summary
    raw_answers = {}

    class RecordingClient(RagEvaluationClient):
        async def answer(self, item, *, mode, top_k):
            attempt = {"id": item.id, "startedAt": datetime.now(timezone.utc).isoformat()}
            try:
                response, latency = await super().answer(item, mode=mode, top_k=top_k)
                # Only the known public answer schema; never serialize client/config/headers.
                safe = {key: response.get(key) for key in
                        ("answer", "abstained", "grounded", "reason", "model", "promptVersion", "citations")}
                raw_answers[item.id] = safe
                attempt.update(response=safe, latencyMs=latency)
                return response, latency
            except Exception as error:
                attempt["errorType"] = type(error).__name__
                raise
            finally:
                with (output / "answer-attempts.jsonl").open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps(attempt, ensure_ascii=False) + "\n")

    async with RecordingClient(base_url="http://127.0.0.1:8000", api_key=settings.rag_internal_api_key) as client:
        report = await asyncio.wait_for(evaluate_dataset(
            dataset, client=client, modes=["hybrid"], top_k=3, evaluate_answers=True,
            llm_interval_seconds=30,
        ), timeout=1500)
    protocol.attach_evidence_metrics(report, sidecar, binding)
    report["answerResponses"] = raw_answers
    report["observedAskLatency"] = latency_summary([
        row.get("answer", {}).get("metrics", {}).get("latencyMs") for row in report["modes"]["hybrid"]["items"]])
    failures = contract_failures(report, raw_answers, 22)
    report["qualityGate"] = {"passed": not failures, "failures": failures,
                             "kind": "completion-scope-citation-page-abstention-contract"}
    report["semanticCorrectnessGate"] = {"passed": None, "reason": "Source-entailment review is separate; lexical coverage is not answer correctness."}
    report["limitations"].extend([
        "Fixed English same-document split; assistant source labels are not independently human-reviewed.",
        "One full split, hybrid only, 30-second pacing; no judge, automatic rerun, subset stitching or ranking/prompt tuning.",
        "Provider failures remain in all denominators. A contract PASS is not semantic answer accuracy.",
        "Latency is sequential internal-VPS Ask including retrieval/generation, not a browser SLA or concurrent load test.",
    ])
    return report, failures


async def run(args):
    from app.core.config import get_settings
    from app.evaluation.production_verification import inventory
    from app.evaluation.run_eval import write_reports
    args.stage = "validate-input"
    if args.document_id <= 0 or not re.fullmatch(r"[a-f0-9]{40}", args.helper_commit):
        raise ValueError("Positive document ID and exact helper revision required.")
    if not re.fullmatch(r"ghcr\.io/phandinhphuc1234/se330-rag@sha256:[a-f0-9]{64}", args.runtime_image):
        raise ValueError("Pinned production image required.")
    if args.phase != "plan" and not re.fullmatch(r"[a-f0-9]{64}", args.candidate_sha):
        raise ValueError("Answer runs require the previously reviewed plan digest.")
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    args.output_created = True
    settings = get_settings()
    if settings.app_env != "production" or not settings.rag_internal_api_key:
        raise ValueError("Use the authorized production RAG runtime.")
    reference = json.loads((ROOT / "tests/fixtures/corpora/postgresql16_concurrency_v1/ingestion-v1.json").read_text(encoding="utf-8"))
    binding = protocol.load_binding()
    documents = await inventory()
    document, dataset, sidecar = select_split(reference, documents, args.document_id,
                                             "dev" if args.phase == "plan" else args.phase, binding)
    manifest, digest = candidate_manifest(settings, args.runtime_image, args.helper_commit, reference)
    (args.output / "candidate.json").write_text(json.dumps({"sha256": digest, "manifest": manifest}, indent=2), encoding="utf-8")
    if args.phase != "plan" and digest != args.candidate_sha:
        raise ValueError("Frozen candidate differs from the reviewed provider-free plan.")
    args.stage = "qdrant-preflight"
    points = await protocol.inspect_qdrant(settings, document, reference["pageCount"])
    (args.output / "qdrant-preflight.json").write_text(json.dumps(points, indent=2), encoding="utf-8")
    (args.output / "inventory.json").write_text(json.dumps({"documents": [document]}, indent=2), encoding="utf-8")
    if args.phase == "plan":
        print(json.dumps({"status": "PLAN_READY_NO_GENERATION", "candidateSha256": digest, "points": points}))
        return 0
    (args.output / f"bound-{args.phase}.json").write_text(dataset.model_dump_json(by_alias=True, indent=2), encoding="utf-8")
    (args.output / "evidence-groups.json").write_text(json.dumps(sidecar, indent=2), encoding="utf-8")
    args.stage = "full-split-answers"
    report, failures = await measure(dataset, sidecar, settings, binding, args.output)
    report["runtime"] = {"phase": args.phase, "candidateSha256": digest, "manifest": manifest,
                         "generationEnabled": True, "heldoutRun": args.phase == "heldout"}
    write_reports(report, args.output)
    print(json.dumps({"phase": args.phase, "qualityGate": report["qualityGate"],
                      "summary": report["modes"]["hybrid"]["summary"], "semanticCorrectnessGate": report["semanticCorrectnessGate"]}))
    return int(bool(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["plan", "dev", "heldout"], required=True)
    parser.add_argument("--document-id", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--helper-commit", required=True)
    parser.add_argument("--runtime-image", required=True)
    parser.add_argument("--candidate-sha", default="")
    args = parser.parse_args()
    try:
        return asyncio.run(run(args))
    except Exception as error:
        safe = {"status": "FAILED", "errorType": type(error).__name__, "phase": args.phase,
                "stage": getattr(args, "stage", "startup"), "generationEnabled": args.phase != "plan"}
        if getattr(args, "output_created", False):
            (args.output / "failure.json").write_text(json.dumps(safe, indent=2), encoding="utf-8")
        print(json.dumps(safe))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
