"""Compare preregistered hybrid policies; cache-only, never auto-promote runtime.

Requires both baseline reports and checksum-pinned sources. Source labels are
used only in evaluation, never in a scorer, settings update or query rewrite.
"""

from __future__ import annotations

import argparse
import asyncio
from contextlib import redirect_stdout
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

from app.evaluation.chunking_comparison import (
    build_chunk_result, evaluate_version, load_dataset, load_holdout_baseline, validate_holdout_anchors,
)
from app.evaluation.hybrid_policy_comparison import (
    POLICIES, experiment_hash, experiment_manifest, metric_summary, review_policies,
)
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.retrieval.reranker import Reranker
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_DATASET, DEFAULT_PDF_DIR, json_write_new, parse_pdf
from scripts.trace_hybrid_retrieval import CacheOnlyQueryProvider, trace_settings


DEFAULT_VI_DATASET = DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json")


def read_reference(path, dataset, settings):
    payload = json.loads(path.read_text(encoding="utf-8"))
    canonical_hash = hashlib.sha256(json.dumps(dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    if payload.get("errorCount") != 0 or payload.get("dataset", {}).get("manifestContentSha256") != canonical_hash:
        raise ValueError("Baseline reference failed or belongs to a different dataset.")
    embedding = payload.get("embedding", {})
    if embedding.get("status") != "passed" or any(embedding.get(key) != getattr(settings, field) for key, field in (
        ("model", "embedding_model"), ("dimension", "embedding_dim"),
        ("version", "embedding_version"), ("textPolicy", "embedding_text_policy"),
    )):
        raise ValueError("Reference embedding identity does not match cached experiment identity.")
    expected = {"chunkSize": 512, "chunkOverlap": 64, "topK": [1, 3, 5], "contextSeedK": 3,
                "contextWindow": 1, "contextBudgetCharacters": 12000, "sameParserAndCleanedText": True}
    if any(payload.get("config", {}).get(key) != value for key, value in expected.items()):
        raise ValueError("Baseline reference processing/ranking/context configuration differs.")
    by_book = {book["id"]: book for book in payload["books"]}
    if set(by_book) != {book.id for book in dataset.books} or any(by_book[book.id]["sha256"] != book.sha256 for book in dataset.books):
        raise ValueError("Baseline reference source checksums differ.")
    return by_book


def without_diagnostics(retrieval):
    result = deepcopy(retrieval)
    for measured in result.values():
        for case in measured["cases"]:
            case.pop("rankingDiagnostics", None)
    return result


async def build_comparison(english_dataset, vi_dataset, pdf_dir, *, english_reference, vi_reference):
    # Guard labels/checksums and references BEFORE parsing or touching vectors.
    load_holdout_baseline(english_dataset, DEFAULT_DATASET)
    baseline = load_holdout_baseline(vi_dataset, DEFAULT_DATASET)
    if english_dataset.query_language != "en" or vi_dataset.query_language != "vi":
        raise ValueError("The experiment requires English development and Vietnamese regression query sets.")
    settings = trace_settings()
    if (settings.hybrid_candidate_multiplier != 3 or settings.hybrid_rrf_k != 60
            or settings.query_rewrite_max_queries != 2 or settings.context_expansion_window != 1
            or settings.hybrid_vector_weight != .7 or settings.hybrid_keyword_weight != .3
            or Reranker().weights != {"rrf": .4, "cosine": .5, "lexical": .1}):
        raise ValueError("Experiment settings drifted from preregistration.")
    inputs = (("english-development", english_dataset, english_reference),
              ("vi-regression", vi_dataset, vi_reference))
    references = {key: read_reference(path, dataset, settings) for key, dataset, path in inputs}
    frozen_experiment = experiment_manifest()
    frozen_hash = experiment_hash()
    builder = EmbeddingTextBuilder(settings=settings)
    cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0)
    query_provider = CacheOnlyQueryProvider(cache)
    prepared = []
    for dataset_id, dataset, _ in inputs:
        for document_id, book in enumerate(dataset.books, 900001):
            parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
            for chunker in ("v1", "v2"):
                result = build_chunk_result(parsed, book, version=chunker, document_id=document_id)
                pages = {page.metadata["page_number"]: page.text for page in result.cleaned_documents}
                if dataset_id == "vi-regression" and baseline:
                    validate_holdout_anchors(book, baseline[book.id], pages)
                vectors = [cache.read("document", builder.build_document_text(chunk.text, chunk.metadata).text)
                           for chunk in result.chunks]
                if any(vector is None for vector in vectors):
                    raise ValueError("Real document cache missing; provider calls forbidden.")
                for question in book.questions:
                    if cache.read("query", builder.build_query_text(question.question).text) is None:
                        raise ValueError("Real query cache missing; provider calls forbidden.")
                prepared.append((dataset_id, book, chunker, document_id, result, vectors))
    report = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "experiment": frozen_experiment, "experimentSha256": frozen_hash,
              "providerCalls": 0, "runtimeWrites": False, "automaticPromotion": False, "errorCount": 0,
              "embedding": {"model": settings.embedding_model, "dimension": settings.embedding_dim,
                            "version": settings.embedding_version, "textPolicy": settings.embedding_text_policy,
                            "cacheOnly": True},
              "datasets": [{"id": key, "name": dataset.name, "version": dataset.version,
                            "sourceLanguage": dataset.source_language, "queryLanguage": dataset.query_language,
                            "reviewStatus": dataset.review_status, "usage": "development/regression, not blind",
                            "manifestContentSha256": hashlib.sha256(json.dumps(
                                dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
                            "referenceSha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                           for key, dataset, path in inputs],
              "segments": [], "limitations": [
                  "Two previously used English books; no native Vietnamese or unseen-source validation.",
                  "Known development/regression queries; no claim of unbiased held-out performance.",
                  "Exact cached cosine, not live Qdrant/Postgres latency or LLM answer quality.",
                  "Candidate nomination is not runtime adoption or a v2 chunker promotion."]}
    for dataset_id, book, chunker, document_id, result, vectors in prepared:
        print(f"Comparing {dataset_id}/{book.id}/{chunker} ...", file=sys.stderr, flush=True)
        segment = {"datasetId": dataset_id, "bookId": book.id, "sourceSha256": book.sha256,
                   "chunker": chunker, "chunkCount": len(result.chunks), "policies": {}}
        for policy in POLICIES:
            modes = ("bm25", "dense", "hybrid") if policy.id == "baseline_v1" else ("hybrid",)
            measured = await evaluate_version(book, result, document_id=document_id, modes=modes,
                                             settings=policy.apply(settings), vectors=vectors,
                                             query_provider=query_provider, reranker=policy.reranker(),
                                             collect_ranking_diagnostics=True)
            if policy.id == "baseline_v1":
                reference = references[dataset_id][book.id]["versions"][chunker]
                if without_diagnostics(measured["retrieval"]) != reference["retrieval"]:
                    raise ValueError("Baseline replay differs; candidate comparisons are not valid.")
                if measured["cleanedPageHashes"] != reference["cleanedPageHashes"]:
                    raise ValueError("Cleaned source identity changed from the reference.")
                segment["baselineAllCasesEqual"] = True
                segment["controls"] = {key: measured["retrieval"][key] for key in ("bm25", "dense")}
            segment["policies"][policy.id] = measured["retrieval"]["hybrid"]
        report["segments"].append(segment)
    if experiment_hash() != frozen_hash:
        raise ValueError("Policy manifest changed while the experiment was running.")
    report["caseCount"] = sum(len(value["cases"]) for segment in report["segments"]
                              for value in (*segment["policies"].values(), *segment["controls"].values()))
    report["decision"] = review_policies(report["segments"])
    return report


def markdown_summary(report):
    rows = ["# Hybrid policy comparison (offline)", "", f"Experiment: `{report['experimentSha256']}`",
            "", f"Cases: {report['caseCount']}; provider calls=0; runtime writes=false.",
            "", "| Dataset | Book | Chunker | Policy/control | Recall@3 | MRR@5 | Context retained |",
            "| --- | --- | --- | --- | ---: | ---: | ---: |"]
    for segment in report["segments"]:
        for key, value in (*segment["controls"].items(), *segment["policies"].items()):
            metrics = metric_summary(value)
            rows.append(f"| {segment['datasetId']} | {segment['bookId']} | {segment['chunker']} | {key} | "
                        f"{metrics['recall3']:.3f} | {metrics['mrr5']:.3f} | {metrics['contextRetention']:.3f} |")
    rows.extend(["", "## Review", "", "| Policy | Eligible for fresh validation | Regressions |",
                 "| --- | --- | ---: |"])
    for review in report["decision"]["reviews"]:
        rows.append(f"| {review['policyId']} | {review['eligibleForFreshValidation']} | {len(review['regressions'])} |")
    rows.extend(["", f"Nominee: {report['decision']['nomineeForFreshValidation'] or 'none'}.",
                 report["decision"]["recommendation"], "", "Not a blind benchmark, native-Vietnamese test or production quality claim.", ""])
    return "\n".join(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--english-dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--vi-dataset", type=Path, default=DEFAULT_VI_DATASET)
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--english-reference", type=Path, default=DEFAULT_PDF_DIR / "hybrid-trace-english-replay-16.json")
    parser.add_argument("--vi-reference", type=Path, default=DEFAULT_PDF_DIR / "hybrid-trace-vi-replay-15.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose new JSON/MD outputs; never overwrite existing files.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_comparison(load_dataset(args.english_dataset), load_dataset(args.vi_dataset),
                                                 args.pdf_dir, english_reference=args.english_reference,
                                                 vi_reference=args.vi_reference))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_summary(report))
        print(f"Saved {args.output}; cases={report['caseCount']}; provider calls=0; runtime writes=false.")
        print(f"Nominee for fresh validation: {report['decision']['nomineeForFreshValidation'] or 'none'}.")
        return 0
    except Exception as error:
        print(f"Comparison failed ({type(error).__name__}); check manifest/reference/source/cache/output. Raw error omitted.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: validate new output -> pin fixed policies/review rules -> validate both
# manifests/references -> validate PDFs and source anchors -> require all cached
# real vectors -> replay baseline exactly -> run the same pipeline/context under
# four fixed alternatives -> review all segment/case losses -> NEW private JSON/MD.
# Purpose: nominate a general ranking policy for FRESH validation without using
# labels inside ranking, calling providers, hiding regressions or changing runtime.
