"""Repeatable RAG evaluation. Keys are read from the environment, never reports."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
from statistics import fmean
import subprocess
from time import perf_counter

import httpx

from app.evaluation.answer_metrics import answer_point_coverage
from app.core.exceptions import LLMError
from app.evaluation.dataset import EvaluationDataset, EvaluationItem, load_evaluation_dataset, select_evaluation_items
from app.evaluation.hallucination_checker import abstention_accuracy, citation_precision, citation_recall
from app.evaluation.retrieval_metrics import (
    hit_rate_at_k, ndcg_at_k, precision_at_k, recall_at_k, reciprocal_rank,
)


DEFAULT_DATASET = Path(__file__).parent / "datasets" / "gift_of_the_magi_v1.json"
SAFE_ERROR_CODES = {
    "LLM_RATE_LIMITED", "LLM_TIMEOUT", "LLM_PROVIDER_FAILED", "LLM_OUTPUT_TRUNCATED",
    "LLM_INVALID_RESPONSE", "VECTOR_RETRIEVAL_FAILED", "ANSWER_GENERATION_FAILED",
}


class LLMRequestPacer:
    """Share one pacing budget across answer and judge calls in a run.

    An answer and its judge both consume generation quota. Pacing only each
    evaluation item would still send twice as many provider calls as intended.
    This is a benchmark control, not a distributed production rate limiter.
    """

    def __init__(self, interval_seconds: float = 0.0, *, clock=perf_counter, sleep=asyncio.sleep) -> None:
        if not math.isfinite(interval_seconds) or not 0 <= interval_seconds <= 60:
            raise ValueError("LLM request interval must be between 0 and 60 seconds.")
        self.interval_seconds = interval_seconds
        self.clock, self.sleep = clock, sleep
        self.last_started_at: float | None = None

    async def wait(self) -> None:
        if self.interval_seconds == 0:
            return
        now = self.clock()
        if self.last_started_at is not None:
            delay = self.interval_seconds - (now - self.last_started_at)
            if delay > 0:
                await self.sleep(delay)
        self.last_started_at = self.clock()


class RagEvaluationClient:
    def __init__(self, *, base_url: str, api_key: str, transport=None) -> None:
        self.client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"), headers={"X-RAG-API-Key": api_key},
            timeout=90.0, transport=transport,
        )

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        await self.client.aclose()

    async def search(self, item: EvaluationItem, *, mode: str, top_k: int):
        payload = item.model_dump(by_alias=True, include={"book_id", "ebook_id", "document_id"}, exclude_none=True)
        payload.update(query=item.question, topK=top_k, retrievalMode=mode)
        return await self._post("/internal/retrieval/search", payload)

    async def answer(self, item: EvaluationItem, *, mode: str, top_k: int):
        if item.ebook_id is None:
            raise ValueError("Answer evaluation requires ebookId.")
        return await self._post("/internal/answers", {
            "question": item.question, "ebookId": item.ebook_id,
            "topK": min(top_k, 20), "retrievalMode": mode,
        })

    async def _post(self, path: str, payload: dict):
        started = perf_counter()
        response = await self.client.post(path, json=payload)
        response.raise_for_status()
        return response.json(), (perf_counter() - started) * 1000


async def evaluate_dataset(
    dataset: EvaluationDataset, *, client, modes: list[str], top_k: int,
    evaluate_answers: bool = True,
    judge=None,
    llm_interval_seconds: float = 0.0,
) -> dict:
    pacer = LLMRequestPacer(llm_interval_seconds)
    mode_reports = {}
    for mode in modes:
        items = []
        for item in dataset.items:
            row = {"id": item.id, "question": item.question, "answerable": item.answerable, "tags": item.tags}
            phase = "retrieval"
            try:
                retrieval, latency = await client.search(item, mode=mode, top_k=top_k)
                retrieved_ids = list(dict.fromkeys(
                    hit.get("vectorId") or hit.get("pointId") for hit in retrieval.get("results", [])
                ))
                expected = set(item.expected_chunk_ids)
                row["retrievedChunkIds"] = retrieved_ids
                row["embeddingVersion"] = retrieval.get("embeddingVersion")
                row["chunkingVersions"] = sorted({
                    str(hit["metadata"]["chunking_strategy_version"])
                    for hit in retrieval.get("results", [])
                    if hit.get("metadata", {}).get("chunking_strategy_version") is not None
                })
                row["retrievalSources"] = sorted({
                    source for hit in retrieval.get("results", [])
                    for source in hit.get("metadata", {}).get("retrieval_sources", ["vector"])
                })
                row["retrieval"] = {
                    f"precision@{top_k}": precision_at_k(expected, retrieved_ids, top_k),
                    f"recall@{top_k}": recall_at_k(expected, retrieved_ids, top_k),
                    f"hitRate@{top_k}": hit_rate_at_k(expected, retrieved_ids, top_k),
                    "reciprocalRank": reciprocal_rank(expected, retrieved_ids),
                    f"nDCG@{top_k}": ndcg_at_k(expected, retrieved_ids, top_k),
                    "latencyMs": latency,
                }
                scope_violations = sum(
                    not _hit_in_scope(hit.get("citation", {}), item)
                    for hit in retrieval.get("results", [])
                )
                row["scopeViolations"] = scope_violations
                if evaluate_answers:
                    await pacer.wait()
                    phase = "answer"
                    answer, answer_latency = await client.answer(item, mode=mode, top_k=top_k)
                    citations = answer.get("citations") or []
                    cited_ids = [citation.get("chunkId") for citation in citations if citation.get("chunkId")]
                    row["scopeViolations"] += sum(not _hit_in_scope(citation, item) for citation in citations)
                    row["citationMetadataViolations"] = sum(
                        not isinstance(citation.get("pageStart"), int)
                        or isinstance(citation.get("pageStart"), bool)
                        or citation["pageStart"] <= 0
                        for citation in citations
                    )
                    row["answer"] = {
                        "text": answer.get("answer", ""), "abstained": bool(answer.get("abstained")),
                        "model": answer.get("model"), "promptVersion": answer.get("promptVersion"),
                        "reason": answer.get("reason"), "citedChunkIds": cited_ids,
                        "metrics": {
                            "answerPointCoverage": answer_point_coverage(item.expected_answer_points, answer.get("answer", "")),
                            "citationPrecision": citation_precision(expected, cited_ids),
                            "citationRecall": citation_recall(expected, cited_ids),
                            "abstentionAccuracy": abstention_accuracy(
                                expected_answerable=item.answerable, abstained=bool(answer.get("abstained")),
                            ),
                            "latencyMs": answer_latency,
                        },
                    }
                    if judge is not None and not answer.get("abstained"):
                        phase = "judge"
                        available = {hit.get("vectorId") or hit.get("pointId"): hit.get("text", "")
                                     for hit in retrieval.get("results", [])}
                        evidence = {citation["chunkId"]: available.get(citation["chunkId"], citation.get("excerpt", ""))
                                    for citation in citations if citation.get("chunkId")}
                        await pacer.wait()
                        verdict = await judge.evaluate(answer.get("answer", ""), evidence)
                        row["answer"]["judge"] = verdict.model_dump(by_alias=True)
                        row["answer"]["judgeEvidenceUsesExcerpts"] = any(key not in available for key in evidence)
            except Exception as error:
                # Keep evaluating remaining cases. Do not copy error messages,
                # which can contain URLs/keys, into committed reports.
                row["errorType"] = type(error).__name__
                row["errorPhase"] = phase
                code = None
                if isinstance(error, httpx.HTTPStatusError):
                    row["httpStatus"] = error.response.status_code
                    try:
                        payload = error.response.json()
                        code = payload.get("error_code") if isinstance(payload, dict) else None
                    except ValueError:
                        pass
                elif isinstance(error, LLMError):
                    code = error.error_code
                if isinstance(code, str) and code in SAFE_ERROR_CODES:
                    row["errorCode"] = code
            items.append(row)
        mode_reports[mode] = {"summary": summarize(items, top_k=top_k), "items": items}
    return {
        "dataset": {"name": dataset.name, "version": dataset.version, "itemCount": len(dataset.items),
                    "selectedItemIds": [item.id for item in dataset.items]},
        "generatedAt": datetime.now(timezone.utc).isoformat(), "topK": top_k,
        "code": _code_provenance(),
        "llmRequestIntervalSeconds": llm_interval_seconds,
        "modes": mode_reports,
        "limitations": [
            "Golden labels apply to the supplied PDF/chunk version; re-label after re-chunking.",
            "Answer-point coverage is lexical and is not a semantic faithfulness score.",
            "Citation relevance does not prove every claim is entailed by the source.",
        ],
    }


def _code_provenance() -> dict:
    repository = Path(__file__).resolve().parents[3]
    try:
        revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=repository, check=True,
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()
        status = subprocess.run(
            ["git", "status", "--porcelain"], cwd=repository, check=True,
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()
        return {"commit": revision, "workingTreeDirty": bool(status)}
    except (OSError, subprocess.SubprocessError):
        return {"commit": None, "workingTreeDirty": None}


def quality_gate_failures(
    report: dict, *, min_hit_rate: float = 0.0, min_recall: float = 0.0,
    gate_modes: list[str] | None = None, require_correct_abstention: bool = False,
) -> list[str]:
    """Operational/scope failures always fail; quality thresholds select a mode."""

    failures = []
    selected_modes = gate_modes or list(report["modes"])
    if any(mode not in report["modes"] for mode in selected_modes):
        raise ValueError("Quality gate modes must be included in the evaluated modes.")
    k = report["topK"]
    for mode, data in report["modes"].items():
        summary = data["summary"]
        for name in ("errorCount", "scopeViolations", "citationMetadataViolations"):
            if summary.get(name, 0):
                failures.append(f"{mode}:{name}")
        if mode in selected_modes:
            if summary[f"hitRate@{k}"] < min_hit_rate:
                failures.append(f"{mode}:hitRate@{k}")
            if summary[f"recall@{k}"] < min_recall:
                failures.append(f"{mode}:recall@{k}")
            if require_correct_abstention:
                if summary.get("answer.evaluatedCount", 0) != report["dataset"]["itemCount"]:
                    failures.append(f"{mode}:incompleteAnswers")
                elif any(row["answer"]["abstained"] != (not row["answerable"]) for row in data["items"]):
                    failures.append(f"{mode}:abstentionMismatch")
    return failures


def _hit_in_scope(citation: dict, item: EvaluationItem) -> bool:
    return all(
        expected is None or citation.get(key) == expected
        for key, expected in (("ebookId", item.ebook_id), ("bookId", item.book_id), ("documentInternalId", item.document_id))
    )


def summarize(items: list[dict], *, top_k: int) -> dict:
    answerable = [item for item in items if item["answerable"]]
    summary = {"errorCount": sum("errorType" in item for item in items),
               "scopeViolations": sum(item.get("scopeViolations", 0) for item in items),
               "citationMetadataViolations": sum(item.get("citationMetadataViolations", 0) for item in items)}
    for name in (f"precision@{top_k}", f"recall@{top_k}", f"hitRate@{top_k}", "reciprocalRank", f"nDCG@{top_k}"):
        summary[name] = fmean(item.get("retrieval", {}).get(name, 0.0) for item in answerable) if answerable else 0.0
    latencies = [item["retrieval"]["latencyMs"] for item in items if "retrieval" in item]
    summary["retrievalLatencyMs"] = fmean(latencies) if latencies else 0.0
    if any("answer" in item for item in items):
        summary["answer.evaluatedCount"] = sum("answer" in item for item in items)
        for name in ("answerPointCoverage", "citationPrecision", "citationRecall", "abstentionAccuracy"):
            selected = items if name == "abstentionAccuracy" else answerable
            summary[f"answer.{name}"] = fmean(
                item.get("answer", {}).get("metrics", {}).get(name, 0.0) for item in selected
            ) if selected else 0.0
    verdicts = [item["answer"]["judge"] for item in items if "judge" in item.get("answer", {})]
    if verdicts:
        summary["judge.evaluatedCount"] = len(verdicts)
        summary["judge.meanFaithfulness"] = fmean(verdict["faithfulness"] for verdict in verdicts)
    return summary


def write_reports(report: dict, output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    json_path, md_path = output_dir / f"rag-eval-{stamp}.json", output_dir / f"rag-eval-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    k = report["topK"]
    lines = [f"# RAG evaluation: {report['dataset']['name']}", "",
             f"Dataset {report['dataset']['version']}; {report['dataset']['itemCount']} cases; top K = {k}.", "",
             "| Mode | Hit rate | Recall | MRR | nDCG | Mean retrieval ms | Errors | Scope violations |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    if any(data["summary"]["errorCount"] for data in report["modes"].values()):
        lines[4:4] = ["This run has operational errors and is not a passing quality baseline. Failed cases remain in metric denominators.", ""]
    answer_lines = []
    for mode, data in report["modes"].items():
        s = data["summary"]
        lines.append(f"| {mode} | {s[f'hitRate@{k}']:.3f} | {s[f'recall@{k}']:.3f} | {s['reciprocalRank']:.3f} | {s[f'nDCG@{k}']:.3f} | {s['retrievalLatencyMs']:.1f} | {s['errorCount']} | {s['scopeViolations']} |")
        if "answer.abstentionAccuracy" in s:
            answer_lines.append(f"- {mode}: abstention accuracy = {s['answer.abstentionAccuracy']:.3f}; citation precision = {s['answer.citationPrecision']:.3f}; lexical answer-point coverage = {s['answer.answerPointCoverage']:.3f}.")
        if "judge.meanFaithfulness" in s:
            answer_lines.append(f"- {mode}: LLM-judge mean faithfulness estimate = {s['judge.meanFaithfulness']:.3f} across {s['judge.evaluatedCount']} answered cases; this is not a proof of correctness.")
    if answer_lines:
        lines.extend(["", "## Answer metrics", ""] + answer_lines)
    lines.extend(["", "## Interpretation limits", ""] + [f"- {item}" for item in report["limitations"]])
    provenance = report.get("code", {})
    lines.extend(["", f"Code commit: `{provenance.get('commit')}`; working tree dirty: `{provenance.get('workingTreeDirty')}`."])
    if "qualityGate" in report:
        gate = report["qualityGate"]
        lines.extend(["", f"Quality gate: **{'PASS' if gate['passed'] else 'FAIL'}**."])
        lines.extend(f"- {failure}" for failure in gate["failures"])
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


async def _run(args) -> int:
    if not args.api_key:
        raise SystemExit("Set RAG_INTERNAL_API_KEY before running evaluation.")
    if not 1 <= args.top_k <= 50:
        raise SystemExit("--top-k must be between 1 and 50.")
    if not math.isfinite(args.llm_interval_seconds) or not 0 <= args.llm_interval_seconds <= 60:
        raise SystemExit("--llm-interval-seconds must be between 0 and 60.")
    for value in (args.min_hit_rate, args.min_recall):
        if not math.isfinite(value) or not 0 <= value <= 1:
            raise SystemExit("Quality thresholds must be between 0 and 1.")
    if args.require_correct_abstention and args.skip_answers:
        raise SystemExit("--require-correct-abstention requires answer evaluation.")
    if args.gate_modes and any(mode not in args.modes for mode in args.gate_modes):
        raise SystemExit("--gate-modes must be included in --modes.")
    judge = None
    if args.judge:
        from app.evaluation.faithfulness_judge import FaithfulnessJudge
        judge = FaithfulnessJudge()
    async with RagEvaluationClient(base_url=args.base_url, api_key=args.api_key) as client:
        dataset = select_evaluation_items(load_evaluation_dataset(args.dataset), args.case_ids)
        report = await evaluate_dataset(dataset, client=client,
                                        modes=args.modes, top_k=args.top_k, evaluate_answers=not args.skip_answers,
                                        judge=judge, llm_interval_seconds=args.llm_interval_seconds)
    failures = quality_gate_failures(
        report, min_hit_rate=args.min_hit_rate, min_recall=args.min_recall,
        gate_modes=args.gate_modes, require_correct_abstention=args.require_correct_abstention,
    )
    report["qualityGate"] = {
        "passed": not failures, "failures": failures,
        "modes": args.gate_modes or args.modes, "minHitRate": args.min_hit_rate,
        "minRecall": args.min_recall, "requireCorrectAbstention": args.require_correct_abstention,
    }
    paths = write_reports(report, args.output_dir)
    for path in paths:
        print(f"Report: {path}")
    for failure in failures:
        print(f"Gate failed: {failure}")
    return int(bool(failures))


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare Library dense/hybrid retrieval and grounded answers.")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--case-ids", nargs="+", help="Run selected golden case IDs; selected IDs are included in the report.")
    parser.add_argument("--base-url", default=os.getenv("RAG_EVAL_BASE_URL", "http://127.0.0.1:8000"))
    parser.add_argument("--api-key", default=os.getenv("RAG_INTERNAL_API_KEY", ""))
    parser.add_argument("--modes", nargs="+", choices=["dense", "hybrid", "graph"], default=["dense", "hybrid"])
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--skip-answers", action="store_true")
    parser.add_argument("--judge", action="store_true", help="Use configured LLM to estimate claim faithfulness (extra provider calls).")
    parser.add_argument("--llm-interval-seconds", type=float, default=0.0,
                        help="Minimum interval shared by answer and judge calls; e.g. 15 for a 5 RPM provider limit.")
    parser.add_argument("--min-hit-rate", type=float, default=0.0)
    parser.add_argument("--min-recall", type=float, default=0.0)
    parser.add_argument("--gate-modes", nargs="+", choices=["dense", "hybrid", "graph"],
                        help="Apply quality thresholds to these modes; operational errors in any mode still fail.")
    parser.add_argument("--require-correct-abstention", action="store_true",
                        help="Require every case to finish and match its answerable/abstained label.")
    parser.add_argument("--output-dir", type=Path, default=Path("app/evaluation/reports"))
    raise SystemExit(asyncio.run(_run(parser.parse_args())))


if __name__ == "__main__":
    main()
