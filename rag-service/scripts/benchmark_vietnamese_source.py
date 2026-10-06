"""Evaluate sealed Vietnamese-source labels with BM25; preflight real caches.

No download, .env access, provider call, HTTP API, database/index write, LLM
generation or policy tuning. Missing vectors are reported as NOT evaluated.
"""

import argparse
import asyncio
from contextlib import redirect_stdout
from pathlib import Path
import sys

from app.evaluation.chunking_comparison import anchor_questions, build_chunk_result
from app.evaluation.vietnamese_source_validation import (
    CONTEXT_BUDGET, DATASET_PATH, EMBEDDING_IDENTITY, inspect_cache,
    registration_manifest, verify_registration,
)
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from scripts.benchmark_book_chunking import (
    CachedGemini, DEFAULT_PDF_DIR, build_report, json_write_new, markdown_report, parse_pdf,
)


def collect_embedding_inputs(dataset, pdf_dir: Path) -> set[tuple[str, str]]:
    # 1. Parse only checksum-pinned bytes, and use the real cleaning/chunking
    # path. Validate all evidence anchors for BOTH versions before cache checks.
    settings = EMBEDDING_IDENTITY.settings()
    builder = EmbeddingTextBuilder(settings=settings)
    inputs = set()
    for document_id, book in enumerate(dataset.books, 900001):
        parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
        for version in ("v1", "v2"):
            result = build_chunk_result(parsed, book, version=version, document_id=document_id)
            if len(result.chunks) > settings.keyword_candidate_limit:
                raise ValueError("Keyword corpus exceeds the complete evaluation limit.")
            anchor_questions(book, {page.metadata["page_number"]: page.text
                                    for page in result.cleaned_documents})
            inputs.update(("document", builder.build_document_text(chunk.text, chunk.metadata).text)
                          for chunk in result.chunks)
        # The actual dense/hybrid path embeds the original query only;
        # conservative rewritten variants are lexical-only inputs.
        inputs.update(("query", builder.build_query_text(question.question).text)
                      for question in book.questions)
    return inputs


def cache_preflight(dataset, pdf_dir: Path) -> dict:
    inputs = collect_embedding_inputs(dataset, pdf_dir)
    cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=EMBEDDING_IDENTITY.settings(), interval=0)
    return EMBEDDING_IDENTITY.report() | inspect_cache(inputs, cache)


async def build_validation_report(pdf_dir: Path, *, dataset_path: Path = DATASET_PATH) -> dict:
    # 2. Reject source-label / previous-policy drift before evaluating BM25.
    # This is a frozen first-source evaluation, not a model-selection loop.
    dataset = verify_registration(dataset_path)
    registration = registration_manifest()
    report = await build_report(dataset, pdf_dir, gemini=False, context_budget=CONTEXT_BUDGET)
    report["registration"] = registration
    report["providerCalls"] = 0
    report["automaticPromotion"] = False
    report["embeddingPreflight"] = cache_preflight(dataset, pdf_dir)
    for entry, book in zip(report["books"], dataset.books, strict=True):
        entry.update(title=book.title, rightsUrl=book.rights_url, license="CC BY 3.0 IGO")
    report["caseCount"] = sum(value["retrieval"]["bm25"]["caseCount"]
                              for book in report["books"] for value in book["versions"].values())
    # 3. Honest scope: an official Vietnamese translation of ONE report is
    # not native-authored literature, human-reviewed or a passing hybrid eval.
    report["limitations"] = [
        "One official Vietnamese-language translated nonfiction report; not Vietnamese-authored novels or broad corpus validation.",
        "Source and labels were frozen before BM25 scoring, but are assistant-source-checked; human review is pending.",
        "Development manifest with a fresh source at first evaluation; after observing results it is regression data, not a reusable blind holdout.",
        "Positive evidence questions only; abstention/negative-question and LLM answer faithfulness are not evaluated.",
        "Narrative chunker section audit includes unnumbered report headings; failures are reported, not silently relabeled.",
        "BM25 and budgeted context are in memory; no live PostgreSQL/Qdrant or latency benchmark.",
        "Embedding preflight checks real cache readiness only; dense/hybrid and sealed adaptive policies are NOT scored in this stage.",
    ]
    report["decision"]["recommendation"] = (
        "HOLD v1 and baseline ranking. Review the questions/source and section audit; "
        "approve a bounded real embedding run before dense/hybrid validation. No policy nomination or automatic promotion.")
    report["decision"]["gates"]["humanReviewCompleted"] = False
    report["decision"]["gates"]["realEmbeddingCacheReady"] = report["embeddingPreflight"]["status"] == "ready"
    # 4. Check the exact source/query and old policy seals again after scoring.
    # Never report a valid comparison if anything changed while it ran.
    verify_registration(dataset_path)
    if registration_manifest() != registration:
        raise ValueError("Evaluation registration changed during scoring.")
    return report


def markdown_summary(report):
    text = markdown_report(report)
    preflight = report["embeddingPreflight"]
    text += ("\n## Source / review provenance\n\n"
             f"Source language: vi. Query language: vi. Human review: pending. Cases: {report['caseCount']}.\n"
             "Official translated nonfiction report; no native-authorship or broad-corpus claim.\n"
             f"Dataset file SHA256: `{report['registration']['datasetFileSha256']}`.\n"
             "\n## Real embedding cache preflight (NOT quality evaluation)\n\n"
             f"Status: {preflight['status']}. Unique inputs: {preflight['uniqueInputCount']}; "
             f"missing: {preflight['missingInputCount']}. Provider calls: 0.\n\n"
             "| Task | Unique inputs | Cached | Missing |\n"
             "| --- | ---: | ---: | ---: |\n")
    for task, counts in preflight["byTask"].items():
        text += f"| {task} | {counts['uniqueInputs']} | {counts['cacheHits']} | {counts['missingInputs']} |\n"
    return text


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--output", type=Path, required=True, help="NEW private JSON and matching Markdown paths.")
    args = parser.parse_args(argv)
    try:
        # 5. Preserve every previous artifact. Do not echo raw SDK/settings
        # errors, secrets, full source text or question text into public logs.
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose unused JSON/Markdown paths; no overwrite allowed.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_validation_report(args.pdf_dir))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_summary(report))
        print(f"Saved {args.output}; {report['caseCount']} BM25 cases; provider calls=0; human review=pending.")
        return 0
    except Exception as error:
        print(f"Vietnamese source evaluation failed ({type(error).__name__}); check seal/source/anchors/cache/output. "
              "Raw error omitted; no valid validation report produced.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: reject output collisions -> verify source/query and old policy seals
# -> checksum/parse/clean/chunk both versions -> score BM25/evidence/context
# -> inspect real cache readiness without fallback -> verify seals again
# -> write NEW private JSON/MD with pending gates and HOLD decision.
# Purpose: add a reproducible Vietnamese-source baseline and actionable review
# material without paid calls, deleting code or changing production behavior.
