"""Run sealed adaptive experiment v2, cache-only and never auto-promote runtime.

Read-only DB/index boundary. Output private NEW reports, never raw settings,
queries, source text or API keys. Known development/regression cases only.
"""

from __future__ import annotations

import argparse
import asyncio
from contextlib import redirect_stdout
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

from app.evaluation.adaptive_hybrid_experiment import (
    ADAPTIVE_POLICIES, AdaptivePolicy, experiment_hash_v2, experiment_manifest_v2,
    review_policies_v2, verify_registration,
)
from app.evaluation.chunking_comparison import (
    build_chunk_result, evaluate_version, load_dataset, load_holdout_baseline, validate_holdout_anchors,
)
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.retrieval.reranker import Reranker
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_DATASET, DEFAULT_PDF_DIR, json_write_new, parse_pdf
from scripts.compare_hybrid_policies import DEFAULT_VI_DATASET, markdown_summary, read_reference, without_diagnostics
from scripts.trace_hybrid_retrieval import CacheOnlyQueryProvider, trace_settings


async def build_comparison(english_dataset, vi_dataset, pdf_dir, *, english_reference, vi_reference):
    # 1. Freeze the registered formulas/gates. Reject provenance/config drift
    # before touching source/cache or evaluating any candidate.
    verify_registration()
    frozen_hash, frozen_manifest = experiment_hash_v2(), experiment_manifest_v2()
    load_holdout_baseline(english_dataset, DEFAULT_DATASET)
    baseline = load_holdout_baseline(vi_dataset, DEFAULT_DATASET)
    if english_dataset.query_language != "en" or vi_dataset.query_language != "vi":
        raise ValueError("Require English development and Vietnamese regression query manifests.")
    settings = trace_settings()
    if (settings.hybrid_candidate_multiplier != 3 or settings.hybrid_rrf_k != 60
            or settings.query_rewrite_max_queries != 2 or settings.context_expansion_window != 1
            or settings.hybrid_vector_weight != .7 or settings.hybrid_keyword_weight != .3
            or Reranker().weights != {"rrf": .4, "cosine": .5, "lexical": .1}):
        raise ValueError("Baseline configuration drifted from preregistration.")
    inputs = (("english-development", english_dataset, english_reference), ("vi-regression", vi_dataset, vi_reference))
    references = {key: read_reference(path, dataset, settings) for key, dataset, path in inputs}
    builder = EmbeddingTextBuilder(settings=settings)
    cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0)
    provider = CacheOnlyQueryProvider(cache)
    prepared = []
    # 2. Check every source checksum/anchor and real embedding cache entry first.
    # Cache.read cannot call a provider. No fake-vector or network fallback.
    for dataset_id, dataset, _ in inputs:
        for document_id, book in enumerate(dataset.books, 900001):
            parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
            for chunker in ("v1", "v2"):
                result = build_chunk_result(parsed, book, version=chunker, document_id=document_id)
                if len(result.chunks) > settings.keyword_candidate_limit:
                    raise ValueError("Full-corpus adaptive strength requires the complete bounded keyword corpus.")
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
              "experiment": frozen_manifest, "experimentSha256": frozen_hash,
              "providerCalls": 0, "runtimeWrites": False, "automaticPromotion": False, "errorCount": 0,
              "embedding": {"model": settings.embedding_model, "dimension": settings.embedding_dim,
                            "version": settings.embedding_version, "textPolicy": settings.embedding_text_policy, "cacheOnly": True},
              "datasets": [{"id": key, "name": dataset.name, "version": dataset.version,
                            "sourceLanguage": dataset.source_language, "queryLanguage": dataset.query_language,
                            "reviewStatus": dataset.review_status, "usage": "development/regression, not blind",
                            "manifestContentSha256": hashlib.sha256(json.dumps(
                                dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
                            "referenceSha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                           for key, dataset, path in inputs],
              "segments": [], "limitations": [
                  "Overlap is descriptive, not calibrated confidence, and does not resolve linguistic mismatch.",
                  "All sources/queries have been observed; not blind, native-Vietnamese or unseen-source validation.",
                  "Exact cached cosine in memory; no live DB/vector latency or LLM answer quality evaluation.",
                  "Full-corpus BM25 profiling adds offline work; no production scalability/latency claim.",
                  "A nominee is for fresh validation only; no runtime adoption, config write or automatic chunker promotion."]}
    for dataset_id, book, chunker, document_id, result, vectors in prepared:
        print(f"Comparing adaptive {dataset_id}/{book.id}/{chunker} ...", file=sys.stderr, flush=True)
        segment = {"datasetId": dataset_id, "bookId": book.id, "sourceSha256": book.sha256,
                   "chunker": chunker, "chunkCount": len(result.chunks), "policies": {}}
        # 3. Replay baseline + controls EXACTLY. For each adaptive alternative,
        # decide using query/corpus text only, then invoke the actual pipeline.
        for policy in ADAPTIVE_POLICIES:
            adaptive = isinstance(policy, AdaptivePolicy)
            measured = await evaluate_version(book, result, document_id=document_id,
                modes=("hybrid",) if adaptive else ("bm25", "dense", "hybrid"), settings=settings,
                vectors=vectors, query_provider=provider, collect_ranking_diagnostics=True,
                per_query_policy=policy if adaptive else None)
            if not adaptive:
                reference = references[dataset_id][book.id]["versions"][chunker]
                if (without_diagnostics(measured["retrieval"]) != reference["retrieval"]
                        or measured["cleanedPageHashes"] != reference["cleanedPageHashes"]):
                    raise ValueError("Baseline replay differs; adaptive comparisons are not valid.")
                segment["baselineAllCasesEqual"] = True
                segment["controls"] = {key: measured["retrieval"][key] for key in ("bm25", "dense")}
            segment["policies"][policy.id] = measured["retrieval"]["hybrid"]
        report["segments"].append(segment)
    # 4. Apply the original segment/case gates + preregistered strict gain rule.
    # Never relax gates or change the registry after observing outcomes.
    verify_registration()
    if experiment_hash_v2() != frozen_hash:
        raise ValueError("Adaptive experiment changed while the runner was executing.")
    report["caseCount"] = sum(len(value["cases"]) for segment in report["segments"]
                              for value in (*segment["policies"].values(), *segment["controls"].values()))
    report["decision"] = review_policies_v2(report["segments"])
    return report


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
        # 5. New files only; keep error messages free of raw secret/SDK data.
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose new JSON/MD outputs; never overwrite existing reports.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_comparison(load_dataset(args.english_dataset), load_dataset(args.vi_dataset), args.pdf_dir,
                                                 english_reference=args.english_reference, vi_reference=args.vi_reference))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_summary(report))
        print(f"Saved {args.output}; {report['caseCount']} cases; provider calls=0; runtime writes=false.")
        print(f"Nominee for FRESH validation: {report['decision']['nomineeForFreshValidation'] or 'none'}.")
        return 0
    except Exception as error:
        print(f"Adaptive comparison failed ({type(error).__name__}); check seal/manifest/reference/source/cache/output. Raw error omitted.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: reject output collisions -> verify preregistration seal and provenance
# -> validate sources/anchors/all real caches -> replay baseline/controls
# -> compute label-free request weights -> run actual retrieval/context pipeline
# -> review every segment/case + strict improvement -> NEW private JSON/MD.
# Purpose: test four fixed adaptive hypotheses without tuning known answers,
# provider calls, raw-text publication or production/index/config changes.
