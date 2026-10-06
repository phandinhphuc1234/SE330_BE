"""Trace the real hybrid pipeline using checksum-pinned PDFs and cached vectors.

No provider, HTTP API, PostgreSQL, Qdrant, ingestion or runtime settings writes.
Keep the new report private: chunk IDs/source locations are diagnostic data.
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

from app.core.config import Settings, get_settings
from app.evaluation.chunking_comparison import (
    LocalCandidateSource, LocalVectorStore, anchor_questions, build_chunk_result,
    load_dataset, load_holdout_baseline, source_ranges, validate_holdout_anchors,
)
from app.evaluation.retrieval_diagnostics import explain_hybrid_trace
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.retrieval.keyword_retriever import KeywordRetriever
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalRequest, LibraryVectorRetrievalService
from app.retrieval.ranking_trace import RankingTrace
from app.retrieval.retrieval_pipeline import RetrievalPipeline
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_DATASET, DEFAULT_PDF_DIR, json_write_new, parse_pdf
from scripts.benchmark_chunking import BASELINE_CONFIG


DEFAULT_TRACE_DATASET = DEFAULT_DATASET.with_name("real_books_vi_query_holdout_v1.json")


class CacheOnlyQueryProvider:
    """Use cache.read directly; a missing entry cannot fall through to Gemini."""

    def __init__(self, cache):
        self.cache = cache

    async def embed_query(self, text):
        vector = self.cache.read("query", text)
        if vector is None:
            raise ValueError("Real query embedding cache missing; no provider calls allowed.")
        return vector


def trace_settings():
    # Copy only public embedding identity; never serialize runtime Settings.
    runtime = get_settings()
    local = Settings.model_construct(**BASELINE_CONFIG, keyword_candidate_limit=2000,
                                     hybrid_fail_open=False, context_expansion_window=1)
    return local.model_copy(update={key: getattr(runtime, key) for key in (
        "embedding_model", "embedding_dim", "embedding_version", "embedding_text_policy")})


async def build_trace_report(dataset, pdf_dir, *, book_id="douglass-narrative",
                             case_ids=("vi-age-estimate", "vi-regular-teacher"), top_k=5):
    if top_k not in (3, 5):
        raise ValueError("Trace top_k must be 3 or 5; use 5 to reproduce the existing benchmark.")
    baseline = load_holdout_baseline(dataset, DEFAULT_DATASET)
    books = [book for book in dataset.books if book_id == "all" or book.id == book_id]
    if not books:
        raise ValueError("Unknown trace book ID.")
    selected = {question.id for book in books for question in book.questions
                if case_ids is None or question.id in case_ids}
    if not selected or (case_ids is not None and selected != set(case_ids)):
        raise ValueError("Unknown or empty trace case IDs.")
    settings = trace_settings()
    builder = EmbeddingTextBuilder(settings=settings)
    cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0)
    prepared = []
    for book in books:
        document_id = 900001 + dataset.books.index(book)
        parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
        for version in ("v1", "v2"):
            result = build_chunk_result(parsed, book, version=version, document_id=document_id)
            pages = {page.metadata["page_number"]: page.text for page in result.cleaned_documents}
            if baseline:
                validate_holdout_anchors(book, baseline[book.id], pages)
            anchored = anchor_questions(book, pages)
            ranges = {chunk.metadata["vector_id"]: source_ranges(chunk, pages) for chunk in result.chunks}
            vectors = [cache.read("document", builder.build_document_text(chunk.text, chunk.metadata).text)
                       for chunk in result.chunks]
            if any(vector is None for vector in vectors):
                raise ValueError("Real document embedding cache missing; no provider calls allowed.")
            questions = [question for question in book.questions if question.id in selected]
            for question in questions:
                if cache.read("query", builder.build_query_text(question.question).text) is None:
                    raise ValueError("Real query embedding cache missing; no provider calls allowed.")
            for chunk in result.chunks:
                chunk.metadata.update(active=True, embedding_version=settings.embedding_version)
            prepared.append((book, version, document_id, result.chunks, vectors, anchored, ranges, questions))

    report = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "dataset": {"name": dataset.name, "version": dataset.version, "split": dataset.split,
                          "queryLanguage": dataset.query_language, "sourceLanguage": dataset.source_language,
                          "reviewStatus": dataset.review_status, "baselineDatasetSha256": dataset.baseline_dataset_sha256,
                          "manifestContentSha256": hashlib.sha256(json.dumps(
                              dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()},
              "providerCalls": 0, "runtimeWrites": False, "automaticPromotion": False, "errorCount": 0,
              "embedding": {"model": settings.embedding_model, "dimension": settings.embedding_dim,
                            "version": settings.embedding_version, "textPolicy": settings.embedding_text_policy,
                            "cacheOnly": True},
              "cases": [], "limitations": [
                  "Observed development/regression ranks, not a new blind evaluation.",
                  "Exact cached cosine in memory, not live Qdrant/Postgres latency or answer quality.",
                  f"topK={top_k} implies candidateK={top_k * settings.hybrid_candidate_multiplier} under the unchanged policy; top-3 recall is measured afterwards."]}
    for book, version, document_id, chunks, vectors, anchored, ranges, questions in prepared:
        dense = LibraryVectorRetrievalService(settings=settings, embedding_provider=CacheOnlyQueryProvider(cache),
                                             vector_store=LocalVectorStore(chunks, vectors))
        keyword = KeywordRetriever(settings=settings, candidate_source=LocalCandidateSource(chunks))
        pipeline = RetrievalPipeline(settings=settings, vector_retriever=dense, keyword_retriever=keyword)
        for question in questions:
            request = LibraryVectorRetrievalRequest(question.question, document_id=document_id, ebook_id=document_id,
                                                     top_k=top_k, retrieval_mode="hybrid", expand_context=False)
            trace = RankingTrace()
            # Run exactly the real orchestration. Labels are only consumed below.
            traced = await pipeline.search(request, trace=trace)
            normal = await pipeline.search(request)
            if traced != normal:
                raise ValueError("Opt-in trace changed retrieval response.")
            report["cases"].append({"bookId": book.id, "sourceSha256": book.sha256,
                                    "chunker": version, "id": question.id,
                                    "querySha256": hashlib.sha256(question.question.encode()).hexdigest(),
                                    "traceInvariantVerified": True,
                                    "trace": explain_hybrid_trace(trace, anchored[question.id], ranges)})
    report["caseCount"] = len(report["cases"])
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_TRACE_DATASET)
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--book-id", default="douglass-narrative", help="Existing book ID, or all")
    parser.add_argument("--case-ids", default="vi-age-estimate,vi-regular-teacher", help="Comma-separated IDs, or all")
    parser.add_argument("--top-k", type=int, choices=(3, 5), default=5)
    args = parser.parse_args(argv)
    try:
        if args.output.suffix != ".json" or args.output.exists():
            raise ValueError("Choose a new JSON output; existing files are never overwritten.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_trace_report(load_dataset(args.dataset), args.pdf_dir, book_id=args.book_id,
                                case_ids=None if args.case_ids == "all" else tuple(args.case_ids.split(",")), top_k=args.top_k))
        json_write_new(args.output, report)
        print(f"Saved {args.output}; {report['caseCount']} traces; provider calls=0; runtime writes=false.")
        return 0
    except Exception as error:
        print(f"Trace failed ({type(error).__name__}); check source labels/checksum/cache/output. Raw error omitted.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: reject output collisions -> validate manifest/baseline/source checksums
# -> parse/clean/chunk v1 and v2 -> require all real cached vectors -> run the
# actual scoped Dense/BM25/RRF/reranker/threshold pipeline with and without trace
# -> verify equal responses -> attach evidence labels AFTER ranking -> NEW JSON.
# Purpose: locate evidence rank loss without tuning weights, provider calls,
# publishing source text, or modifying the database/index/deployed configuration.
