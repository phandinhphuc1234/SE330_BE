"""Explain development-set rank regressions without calling any provider.

Uses checksum-pinned PDFs and existing real embeddings only. Missing cache
is an explicit error, never a lexical vector stand-in or a Gemini call.
"""

import argparse
import asyncio
from contextlib import redirect_stdout
from datetime import datetime, timezone
from pathlib import Path
import sys

from app.core.config import Settings, get_settings
from app.evaluation.chunking_comparison import anchor_questions, build_chunk_result, load_dataset, source_ranges
from app.evaluation.retrieval_diagnostics import explain_dense_ranking, explain_keyword_ranking
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.retrieval.keyword_retriever import KeywordCandidate, tokenize
from app.retrieval.query_rewriter import QueryRewriter
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_DATASET, DEFAULT_PDF_DIR, json_write_new, parse_pdf
from scripts.benchmark_chunking import BASELINE_CONFIG


async def build_diagnostics(dataset, pdf_dir, *, book_id="douglass-narrative", case_ids=("birthplace", "mother-name"),
                            cached_dense=False):
    books = [book for book in dataset.books if book.id == book_id]
    if len(books) != 1:
        raise ValueError("Select exactly one existing book.")
    book = books[0]
    questions = [question for question in book.questions if question.id in case_ids]
    if {question.id for question in questions} != set(case_ids):
        raise ValueError("Unknown diagnostic case ID.")
    document_id = 900001 + dataset.books.index(book)
    parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "bookId": book.id, "sourceSha256": book.sha256,
              "runtimeWrites": False, "providerCalls": 0, "automaticPromotion": False, "versions": {},
              "limitations": ["Development diagnostics, not held-out evaluation.",
                              "Ablations are offline hypotheses, not production query policies."]}
    builder = cache = None
    if cached_dense:
        runtime = get_settings()
        settings = Settings.model_construct(**BASELINE_CONFIG).model_copy(update={
            key: getattr(runtime, key) for key in ("embedding_model", "embedding_dim", "embedding_version", "embedding_text_policy")
        })
        builder = EmbeddingTextBuilder(settings=settings)
        cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0)
        report["embedding"] = {"model": settings.embedding_model, "dimension": settings.embedding_dim,
                               "version": settings.embedding_version, "cacheOnly": True}
    for version in ("v1", "v2"):
        result = build_chunk_result(parsed, book, version=version, document_id=document_id)
        pages = {page.metadata["page_number"]: page.text for page in result.cleaned_documents}
        anchored = anchor_questions(book, pages)
        ranges = {chunk.metadata["vector_id"]: source_ranges(chunk, pages) for chunk in result.chunks}
        candidates = [KeywordCandidate(index + 1, chunk.metadata["vector_id"], chunk.text, dict(chunk.metadata))
                      for index, chunk in enumerate(result.chunks)]
        vectors = None
        if cache:
            vectors = [cache.read("document", builder.build_document_text(chunk.text, chunk.metadata).text)
                       for chunk in result.chunks]
            if any(vector is None for vector in vectors):
                raise ValueError("Real document embedding cache missing; no provider calls allowed.")
        cases = []
        for question in questions:
            variants = await QueryRewriter().rewrite(question.question)
            focused = variants[-1]
            # This ablation tests title-token contamination only. It is NOT
            # wired into KeywordRetriever or approved for general queries.
            title_tokens = set(tokenize(book.title))
            without_title = " ".join(token for token in tokenize(focused) if token not in title_tokens)
            policies = {"original": question.question, "focused": focused}
            if without_title:
                policies["scope_title_removed_ablation"] = without_title
            case = {"id": question.id, "keyword": {name: explain_keyword_ranking(query, candidates, anchored[question.id], ranges)
                                                      for name, query in policies.items()}}
            if cache:
                query_vector = cache.read("query", builder.build_query_text(question.question).text)
                if query_vector is None:
                    raise ValueError("Real query embedding cache missing; no provider calls allowed.")
                case["dense"] = explain_dense_ranking(query_vector, result.chunks, vectors, anchored[question.id], ranges)
            cases.append(case)
        report["versions"][version] = {"chunkCount": len(result.chunks), "cases": cases}
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--book-id", default="douglass-narrative")
    parser.add_argument("--case-ids", default="birthplace,mother-name")
    parser.add_argument("--cached-dense", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.output.suffix != ".json" or args.output.exists():
            raise ValueError("Choose a new JSON output; existing files are never overwritten.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_diagnostics(load_dataset(args.dataset), args.pdf_dir, book_id=args.book_id,
                                                  case_ids=tuple(args.case_ids.split(",")), cached_dense=args.cached_dense))
        json_write_new(args.output, report)
        print(f"Saved {args.output}; provider calls=0; runtime writes=false.")
        return 0
    except Exception as error:
        print(f"Diagnostics failed ({type(error).__name__}); check source labels/checksum/cache/output. Raw error omitted.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: validate output/labels -> validate and parse the same PDFs -> frozen v1 /
# current v2 chunk path -> real BM25 contribution explanations -> optional cached
# Gemini cosine diagnostics -> NEW private JSON. Purpose: identify ranking causes
# without tuning on held-out questions, altering scoring, or calling providers.
