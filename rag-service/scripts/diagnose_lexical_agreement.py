"""Explain lexical agreement on every existing English/Vietnamese regression case.

Offline, cache-only and descriptive: no new ranking policy or automatic tuning.
Private JSON contains query tokens/locations, never full questions/source chunks.
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

from app.evaluation.chunking_comparison import (
    LocalCandidateSource, LocalVectorStore, anchor_questions, build_chunk_result, cosine,
    evaluate_version, load_dataset, load_holdout_baseline, source_ranges, validate_holdout_anchors,
)
from app.evaluation.lexical_agreement_diagnostics import describe_lexical_agreement, summarize_cases
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.retrieval.keyword_retriever import KeywordCandidate, KeywordRetriever
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalRequest, LibraryVectorRetrievalService
from app.retrieval.query_rewriter import QueryRewriter
from app.retrieval.ranking_trace import RankingTrace
from app.retrieval.reranker import Reranker
from app.retrieval.retrieval_pipeline import RetrievalPipeline
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_DATASET, DEFAULT_PDF_DIR, json_write_new, parse_pdf
from scripts.compare_hybrid_policies import DEFAULT_VI_DATASET, read_reference
from scripts.trace_hybrid_retrieval import CacheOnlyQueryProvider, trace_settings


async def build_report(english_dataset, vi_dataset, pdf_dir, *, english_reference, vi_reference):
    # 1. Check source/label provenance and the unchanged baseline BEFORE any I/O
    # beyond manifests/references. An observed query set is a regression set now.
    load_holdout_baseline(english_dataset, DEFAULT_DATASET)
    baseline = load_holdout_baseline(vi_dataset, DEFAULT_DATASET)
    if english_dataset.query_language != "en" or vi_dataset.query_language != "vi":
        raise ValueError("Require the English development and Vietnamese regression manifests.")
    settings = trace_settings()
    if (settings.hybrid_candidate_multiplier != 3 or settings.hybrid_rrf_k != 60
            or settings.query_rewrite_max_queries != 2 or settings.context_expansion_window != 1
            or settings.hybrid_vector_weight != .7 or settings.hybrid_keyword_weight != .3
            or Reranker().weights != {"rrf": .4, "cosine": .5, "lexical": .1}):
        raise ValueError("Lexical diagnosis requires the unchanged baseline scoring configuration.")
    inputs = (("english-development", english_dataset, english_reference),
              ("vi-regression", vi_dataset, vi_reference))
    references = {key: read_reference(path, dataset, settings) for key, dataset, path in inputs}
    builder = EmbeddingTextBuilder(settings=settings)
    cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0)
    provider = CacheOnlyQueryProvider(cache)
    prepared = []
    # 2. Validate PDFs/checksums/cleaned spans and preflight EVERY real cache entry.
    # Calling cache.read cannot fall through to an embedding provider.
    for dataset_id, dataset, _ in inputs:
        for document_id, book in enumerate(dataset.books, 900001):
            parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
            for chunker in ("v1", "v2"):
                result = build_chunk_result(parsed, book, version=chunker, document_id=document_id)
                if len(result.chunks) > settings.keyword_candidate_limit:
                    raise ValueError("Full-corpus diagnostic cannot bypass the scoped keyword candidate limit.")
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
                prepared.append((dataset_id, book, chunker, document_id, result, vectors, pages))
    report = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "diagnosticId": "lexical-agreement-v1", "providerCalls": 0, "runtimeWrites": False,
              "automaticPromotion": False, "errorCount": 0,
              "embedding": {"model": settings.embedding_model, "dimension": settings.embedding_dim,
                            "version": settings.embedding_version, "textPolicy": settings.embedding_text_policy,
                            "cacheOnly": True},
              "config": {"topK": 5, "candidateK": 15, "rrfK": 60, "vectorWeight": .7,
                         "keywordWeight": .3, "rerankerWeights": Reranker().weights,
                         "contextSeedK": 3, "contextWindow": 1, "contextBudgetCharacters": 12000},
              "datasets": [{"id": key, "name": dataset.name, "version": dataset.version,
                            "queryLanguage": dataset.query_language, "sourceLanguage": dataset.source_language,
                            "reviewStatus": dataset.review_status,
                            "manifestContentSha256": hashlib.sha256(json.dumps(
                                dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
                            "referenceSha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                           for key, dataset, path in inputs],
              "cases": [], "limitations": [
                  "Two known English PDFs, development/regression queries; not blind or native-Vietnamese validation.",
                  "IDF-weighted coverage includes DF=0 terms; it is not a semantic confidence score.",
                  "Scope-title terms may be meaningful body terms; title overlap does not imply a boilerplate hit.",
                  "Selected rows include the full RRF union and all source-overlapping evidence, even BM25 score=0.",
                  "Exact cached cosine in memory; no live DB/vector latency or generated-answer quality measurement.",
                  "Features are descriptive only; no new thresholds, weights, routing, policy selection or runtime writes."]}
    for dataset_id, book, chunker, document_id, result, vectors, pages in prepared:
        print(f"Diagnosing {dataset_id}/{book.id}/{chunker} ...", file=sys.stderr, flush=True)
        # 3. Replay actual baseline context/citations/metrics against the old
        # reports, not just an aggregate average, before drawing conclusions.
        replay = await evaluate_version(book, result, document_id=document_id, modes=("hybrid",),
                                        settings=settings, vectors=vectors, query_provider=provider)
        reference = references[dataset_id][book.id]["versions"][chunker]
        if (replay["retrieval"]["hybrid"] != reference["retrieval"]["hybrid"]
                or replay["cleanedPageHashes"] != reference["cleanedPageHashes"]):
            raise ValueError("Baseline/source replay differs; diagnosis cannot be compared fairly.")
        anchored = anchor_questions(book, pages)
        ranges = {chunk.metadata["vector_id"]: source_ranges(chunk, pages) for chunk in result.chunks}
        candidates = [KeywordCandidate(index + 1, chunk.metadata["vector_id"], chunk.text, dict(chunk.metadata))
                      for index, chunk in enumerate(result.chunks)]
        pipeline = RetrievalPipeline(settings=settings, vector_retriever=LibraryVectorRetrievalService(
            settings=settings, embedding_provider=provider, vector_store=LocalVectorStore(result.chunks, vectors)),
            keyword_retriever=KeywordRetriever(settings=settings, candidate_source=LocalCandidateSource(result.chunks)))
        rewriter = QueryRewriter(max_queries=settings.query_rewrite_max_queries)
        for question in book.questions:
            request = LibraryVectorRetrievalRequest(question.question, document_id=document_id, ebook_id=document_id,
                                                     top_k=5, retrieval_mode="hybrid", expand_context=False)
            # 4. Trace the REAL pipeline and assert equality with its normal path.
            trace = RankingTrace()
            traced, normal = await pipeline.search(request, trace=trace), await pipeline.search(request)
            if traced != normal:
                raise ValueError("Lexical diagnostic trace changed the retrieval response.")
            variants = await rewriter.rewrite(question.question)
            query_vector = cache.read("query", builder.build_query_text(question.question).text)
            dense_scores = {chunk.metadata["vector_id"]: cosine(query_vector, vector)
                            for chunk, vector in zip(result.chunks, vectors, strict=True)}
            # 5. Compute label-free token/DF/IDF features and check actual branch
            # scores, THEN add evidence labels. Never serialize raw source text.
            report["cases"].append({"datasetId": dataset_id, "bookId": book.id, "chunker": chunker,
                                    "id": question.id, "sourceSha256": book.sha256,
                                    "querySha256": hashlib.sha256(question.question.encode()).hexdigest(),
                                    "baselineAllCaseFieldsEqual": True, "traceInvariantVerified": True,
                                    "diagnostic": describe_lexical_agreement(variants, candidates, book.title, trace,
                                                                            dense_scores, anchored[question.id], ranges)})
    report["caseCount"] = len(report["cases"])
    report["baselineHybridReplayCaseCount"] = report["caseCount"]
    report["segments"] = summarize_cases(report["cases"])
    return report


def markdown_summary(report):
    lines = ["# Lexical agreement diagnostic (offline)", "",
             f"Cases: {report['caseCount']}; baseline hybrid replay cases: {report['baselineHybridReplayCaseCount']}; provider calls=0.",
             "", "| Dataset | Book | Chunker | Cases | Hybrid recall@3 up/down/equal vs Dense | Focused BM25 top1 title-token-only | No focused BM25 |",
             "| --- | --- | --- | ---: | --- | ---: | ---: |"]
    for segment in report["segments"]:
        delta = segment["hybridVsDenseRecall3"]
        lines.append(f"| {segment['datasetId']} | {segment['bookId']} | {segment['chunker']} | {segment['caseCount']} | "
                     f"{delta['increased']}/{delta['decreased']}/{delta['equal']} | "
                     f"{segment['focusedBm25Top1TitleTokenOnlyCount']} | {segment['noFocusedBm25ResultCount']} |")
    lines.extend(["", "Counts describe observed branches, not a detector, routing rule or causal proof.",
                  "Title-token overlap does not tell where a token occurred; DF=0 terms remain in IDF coverage denominators.",
                  "Development/regression only; runtime defaults unchanged. JSON has private tokens/IDs, no full source chunks.", ""])
    return "\n".join(lines)


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
        # 6. Both outputs must be NEW; never clobber user reports, even if only
        # the Markdown sibling already exists. Errors omit raw SDK/secret data.
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose new JSON/MD outputs; never overwrite existing files.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_report(load_dataset(args.english_dataset), load_dataset(args.vi_dataset), args.pdf_dir,
                                              english_reference=args.english_reference, vi_reference=args.vi_reference))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_summary(report))
        print(f"Saved {args.output}; {report['caseCount']} diagnostics; baseline replay exact; provider calls=0; runtime writes=false.")
        return 0
    except Exception as error:
        print(f"Lexical diagnosis failed ({type(error).__name__}); check manifests/reference/source/cache/output. Raw error omitted.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: reject output collisions -> validate unchanged baseline and both
# manifests/references -> validate PDFs/anchors/cache -> exact baseline replay
# -> real traced/normal retrieval equality -> per-term lexical diagnostics
# -> evidence annotation AFTER scoring -> summarize ALL cases -> NEW private JSON/MD.
# Purpose: understand helpful/noisy lexical matches before preregistering another
# experiment, without tuning known answers or touching production/index/config.
