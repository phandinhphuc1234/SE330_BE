"""Explain the completed Vietnamese baseline with real cache, never a provider.

No ranking/chunking changes, runtime embedding settings selection, LLM calls,
DB/Qdrant writes or tuning. Common app imports may initialize app configuration.
Keep generated reports private: they contain evidence locations and query terms.
"""

from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from contextlib import redirect_stdout
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

from app.evaluation.chunking_comparison import (
    LocalCandidateSource, LocalVectorStore, anchor_questions, build_chunk_result,
    canonical, cosine, evaluate_version, locate_heading_start, source_ranges,
)
from app.evaluation.retrieval_diagnostics import explain_hybrid_trace, profile_keyword_corpus
from app.evaluation.vietnamese_source_validation import (
    CONTEXT_BUDGET, DATASET_PATH, EMBEDDING_IDENTITY, OWNER_REVIEW_PATH,
    registration_manifest, verify_owner_review, verify_registration,
)
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.ingestion.chunking.chapter_boundaries import find_chapter_boundaries
from app.ingestion.chunking.chapter_detector import ChapterDetector
from app.retrieval.keyword_retriever import KeywordCandidate, KeywordRetriever
from app.retrieval.library_vector_retrieval import LibraryVectorRetrievalRequest, LibraryVectorRetrievalService
from app.retrieval.query_rewriter import QueryRewriter
from app.retrieval.ranking_trace import RankingTrace
from app.retrieval.reranker import Reranker
from app.retrieval.retrieval_pipeline import RetrievalPipeline
from scripts.benchmark_book_chunking import CachedGemini, DEFAULT_PDF_DIR, json_write_new, parse_pdf
from scripts.benchmark_vietnamese_embeddings import BASELINE_RANKING, BASELINE_RERANKER
from scripts.benchmark_chunking import BASELINE_CONFIG
from scripts.trace_hybrid_retrieval import CacheOnlyQueryProvider


DEFAULT_BASELINE = DEFAULT_PDF_DIR / "vietnamese-source-cache-replay-28.json"


def expected_reference_config():
    return {"chunkSize": BASELINE_CONFIG["chunk_size"], "chunkOverlap": BASELINE_CONFIG["chunk_overlap"],
            "contextBudgetCharacters": CONTEXT_BUDGET, "contextSeedK": 3, "contextWindow": 1,
            "ranking": dict(BASELINE_RANKING), "rerankerWeights": dict(BASELINE_RERANKER),
            "sameParserAndCleanedText": True, "topK": [1, 3, 5]}


def load_reference(path, dataset, review):
    """Require the completed, owner-reviewed cache-only baseline before work."""
    raw = path.read_bytes()
    reference = json.loads(raw)
    identity = EMBEDDING_IDENTITY.report()
    expected_dataset = {"name": dataset.name, "version": dataset.version, "split": dataset.split,
                        "queryLanguage": dataset.query_language, "sourceLanguage": dataset.source_language,
                        "reviewStatus": dataset.review_status,
                        "baselineDatasetSha256": dataset.baseline_dataset_sha256,
                        "manifestContentSha256": hashlib.sha256(json.dumps(
                            dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()}
    if (reference.get("stage") != "vietnamese-source-owner-reviewed-embedding-baseline-v1"
            or reference.get("errorCount") != 0 or reference.get("caseCount") != 120
            or reference.get("caseCountByMode") != {"bm25": 40, "dense": 40, "hybrid": 40}
            or reference.get("ownerReview") != review
            or reference.get("dataset") != expected_dataset
            or reference.get("firstScoringRegistration") != registration_manifest()
            or reference.get("providerCalls") != 0
            or reference.get("providerAuthorization", {}).get("mode") != "cache_only"
            or reference.get("runtimeWrites") is not False
            or reference.get("llmGeneration") is not False
            or reference.get("automaticPromotion") is not False):
        raise ValueError("Require the completed immutable cache-only baseline and current owner receipt.")
    embedding = reference.get("embedding", {})
    if (embedding.get("status") != "passed"
            or any(embedding.get(key) != value for key, value in identity.items())
            or embedding.get("submittedInputCount") != 0
            or reference.get("config") != expected_reference_config()
            or Reranker().weights != BASELINE_RERANKER):
        raise ValueError("Baseline embedding/ranking identity drifted.")
    books = reference.get("books", [])
    if len(books) != 1 or books[0].get("id") != dataset.books[0].id:
        raise ValueError("Reference source scope differs from sealed labels.")
    return reference, hashlib.sha256(raw).hexdigest()


def heading_diagnostics(book, pages, audit):
    """Compare actual detector outputs with labels; labels cannot guide detection."""
    detector = ChapterDetector()
    boundaries, fence = {}, None
    for page, text in sorted(pages.items()):
        boundaries[page], fence = find_chapter_boundaries(text, fence_state=fence)
    rows = []
    for label in book.chapters:
        text = pages[label.page]
        offset = locate_heading_start(text, label.heading)
        end = text.find("\n", offset)
        line = text[offset:end if end >= 0 else len(text)]
        line_index = sum(bool(item.strip()) for item in text[:offset].splitlines()) + 1
        v1 = detector._detect_heading(text)
        v2 = next((item for item in boundaries[label.page] if item.offset == offset), None)
        rows.append({
            "page": label.page, "reviewedTitle": label.title, "sourceCharStart": offset,
            "nonemptyLineNumber": line_index, "sourceLineCharacters": len(line.strip()),
            "markdownWrapper": bool(re.match(r"\s*(?:#{1,6}\s|\*\*)", line)),
            "v1ScanLimit": detector.max_heading_scan_lines,
            "v1DetectedReviewedHeading": bool(v1 and canonical(v1["title"]) == canonical(label.heading)),
            "v1DetectedPageTitle": v1["title"] if v1 else None,
            "v2DetectedReviewedHeading": v2 is not None,
            "v2DetectedTitle": v2.title if v2 else None,
            "v2PageBoundaryCount": len(boundaries[label.page]),
        })
    groups = Counter((tuple(row["expected"]), row["actual"]) for row in audit["mismatches"])
    return {"reviewedHeadings": rows,
            "v1ReviewedHeadingsDetected": sum(row["v1DetectedReviewedHeading"] for row in rows),
            "v2ReviewedHeadingsDetected": sum(row["v2DetectedReviewedHeading"] for row in rows),
            "mismatchGroups": [{"expected": list(expected), "actual": actual, "chunkCount": count}
                               for (expected, actual), count in sorted(groups.items(), key=lambda item: str(item[0]))]}


def anchor_chunk_rows(anchor, chunks, ranges, dense_rows, lexical_profiles, baseline_cases):
    """Expose full/partial spans, not just binary >=50% MRR relevance.

    Source coverage is diagnostic only; context IDs do not prove that text
    survived compression. Use the baseline's strict context-retention metric.
    """
    rows = []
    for chunk in chunks:
        key = chunk.metadata["vector_id"]
        positions = set()
        for page, start, end in ranges[key]:
            if page == anchor.page:
                positions.update(range(start, end))
        overlap = anchor.positions & positions
        if not overlap:
            continue
        row = {"vectorId": key, "chunkIndex": chunk.metadata["chunk_index"],
               "pageStart": chunk.metadata["pageStart"], "pageEnd": chunk.metadata["pageEnd"],
               "chapterTitle": chunk.metadata.get("chapter_title"), "sectionId": chunk.metadata.get("section_id"),
               "sourceRanges": ranges[key], "chunkCharacters": len(chunk.text),
               "anchorCoveredCharacters": len(overlap), "anchorCoverageFraction": len(overlap) / len(anchor.positions),
               "fullAnchorInThisChunk": anchor.positions <= positions,
               "dense": dense_rows[key],
               "keyword": {name: profile[key] for name, profile in lexical_profiles.items()},
               "membership": {mode: {
                   "top3Seed": key in {item["vectorId"] for item in case["seedCitations"][:3]},
                   "returnedContextId": key in case["contextChunkIds"],
               } for mode, case in baseline_cases.items()}}
        rows.append(row)
    return sorted(rows, key=lambda row: row["chunkIndex"])


async def build_diagnostics(pdf_dir, baseline_path=DEFAULT_BASELINE, *,
                            dataset_path=DATASET_PATH, review_path=OWNER_REVIEW_PATH):
    # 1. Verify labels/receipt/reference and public settings before PDF/cache work.
    dataset = verify_registration(dataset_path)
    review = verify_owner_review(dataset, review_path)
    reference, reference_hash = load_reference(baseline_path, dataset, review)
    settings = EMBEDDING_IDENTITY.settings().model_copy(update=BASELINE_RANKING)
    cache = CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0)
    builder = EmbeddingTextBuilder(settings=settings)
    query_provider = CacheOnlyQueryProvider(cache)  # read-only; never embed() fallback
    book = dataset.books[0]
    document_id = 900001
    parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache")
    report = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "stage": "vietnamese-baseline-diagnostics-v1", "sourceSha256": book.sha256,
              "dataset": reference["dataset"], "ownerReview": review,
              "baselineReferenceSha256": reference_hash, "embedding": EMBEDDING_IDENTITY.report(),
              "ranking": dict(BASELINE_RANKING), "providerCalls": 0, "runtimeWrites": False,
              "llmGeneration": False, "automaticPromotion": False, "errorCount": 0,
              "versions": {}, "limitations": [
                  "Observed development/regression data, not a fresh blind evaluation.",
                  "Cached exact cosine/local keyword corpus, not live Qdrant/Postgres or production latency.",
                  "Common app imports initialize configuration/SQLAlchemy engine; no DB connection/session or secret serialization is performed.",
                  "Evidence labels attach after ranking; no new policy, ablation, weights or query embeddings.",
                  "MRR relevance >=50% of an anchor differs from complete top-k anchor recall.",
                  "Context-ID membership is not post-compression text retention; baseline strict metric is authoritative.",
                  "Rank traces locate observed losses; they do not prove a detector change will fix retrieval.",
              ]}
    for version in ("v1", "v2"):
        # 2. Rebuild unchanged chunks/spans and require every real cache input.
        result = build_chunk_result(parsed, book, version=version, document_id=document_id)
        pages = {page.metadata["page_number"]: page.text for page in result.cleaned_documents}
        anchors = anchor_questions(book, pages)
        chunks = result.chunks
        ranges = {chunk.metadata["vector_id"]: source_ranges(chunk, pages) for chunk in chunks}
        vectors = [cache.read("document", builder.build_document_text(chunk.text, chunk.metadata).text)
                   for chunk in chunks]
        if any(vector is None for vector in vectors):
            raise ValueError("Real document cache missing; no provider fallback.")
        query_vectors = {q.id: cache.read("query", builder.build_query_text(q.question).text) for q in book.questions}
        if any(vector is None for vector in query_vectors.values()):
            raise ValueError("Real query cache missing; no provider fallback.")

        # 3. Replay ALL 120 baseline cases, including context/citations/audits.
        measured = await evaluate_version(book, result, document_id=document_id,
                                          modes=("bm25", "dense", "hybrid"), settings=settings,
                                          vectors=vectors, query_provider=query_provider,
                                          context_budget=CONTEXT_BUDGET)
        if measured != reference["books"][0]["versions"][version]:
            raise ValueError("Actual baseline replay drifted; diagnostics cannot silently change policy.")
        by_mode = {mode: {case["id"]: case for case in value["cases"]}
                   for mode, value in measured["retrieval"].items()}
        heading_report = heading_diagnostics(book, pages, measured["chapterAudit"])
        candidates = [KeywordCandidate(index + 1, chunk.metadata["vector_id"], chunk.text, dict(chunk.metadata))
                      for index, chunk in enumerate(chunks)]
        dense = LibraryVectorRetrievalService(settings=settings, embedding_provider=query_provider,
                                             vector_store=LocalVectorStore(chunks, vectors))
        keyword = KeywordRetriever(settings=settings, candidate_source=LocalCandidateSource(chunks))
        pipeline = RetrievalPipeline(settings=settings, vector_retriever=dense, keyword_retriever=keyword)
        cases = []
        for question in book.questions:
            # 4. Observe real hybrid stages twice; traced/untraced responses MUST agree.
            request = LibraryVectorRetrievalRequest(question.question, document_id=document_id,
                                                     ebook_id=document_id, top_k=5,
                                                     retrieval_mode="hybrid", expand_context=False)
            trace = RankingTrace()
            traced = await pipeline.search(request, trace=trace)
            normal = await pipeline.search(request)
            if traced != normal:
                raise ValueError("Trace changed the actual retrieval response.")
            explained = explain_hybrid_trace(trace, anchors[question.id], ranges)
            ranked = sorted(((cosine(query_vectors[question.id], vector), chunk.metadata["vector_id"])
                             for chunk, vector in zip(chunks, vectors, strict=True)), key=lambda row: (-row[0], row[1]))
            dense_rows = {key: {"rank": rank, "cosine": score} for rank, (score, key) in enumerate(ranked, 1)}
            variants = await QueryRewriter(max_queries=settings.query_rewrite_max_queries).rewrite(question.question)
            profiles = {f"variant{index}": profile_keyword_corpus(query, candidates)
                        for index, query in enumerate(variants)}
            lexical_rows = {name: {row["vectorId"]: {key: value for key, value in row.items()
                                                     if key not in {"vectorId", "pageStart", "pageEnd"}}
                                  for row in profile["results"]} for name, profile in profiles.items()}
            baseline_cases = {mode: cases_by_id[question.id] for mode, cases_by_id in by_mode.items()}
            # Labels are consumed here, AFTER all actual ranking calls above.
            evidence = [{"anchorIndex": index, "page": anchor.page, "characters": len(anchor.positions),
                         "quoteSha256": hashlib.sha256(anchor.quote.encode()).hexdigest(),
                         "chunks": anchor_chunk_rows(anchor, chunks, ranges, dense_rows,
                                                     lexical_rows, baseline_cases)}
                        for index, anchor in enumerate(anchors[question.id])]
            cases.append({"id": question.id, "traceInvariantVerified": True, "hybridTrace": explained,
                          "baseline": baseline_cases, "evidence": evidence,
                          "keywordQueries": {name: {key: value for key, value in profile.items() if key != "results"}
                                             for name, profile in profiles.items()}})
        report["versions"][version] = {"baselineReplayExact": True, "chunkCount": len(chunks),
                                       "chapterAudit": measured["chapterAudit"], "headings": heading_report,
                                       "caseCount": len(cases), "cases": cases}

    # 5. Recheck immutable inputs after measurement, before writing any report.
    verify_registration(dataset_path)
    if (verify_owner_review(dataset, review_path) != review
            or hashlib.sha256(baseline_path.read_bytes()).hexdigest() != reference_hash
            or hashlib.sha256((pdf_dir / book.filename).read_bytes()).hexdigest() != book.sha256
            or Reranker().weights != BASELINE_RERANKER):
        raise ValueError("Reference/source/review/ranking changed during diagnostics.")
    report.update(caseCount=40, baselineReplayCaseCount=120, baselineReplayExact=True)
    return report


def markdown_report(report):
    lines = ["# Vietnamese baseline diagnostics", "", "Cache-only; unchanged ranking/chunkers; HOLD v1.", "",
             f"{report['caseCount']} diagnostic case-version pairs; {report['baselineReplayCaseCount']} exact baseline cases.",
             "Provider calls=0; LLM generation=false; runtime writes=false.", "",
             "| Chunker | Reviewed headings detected | Section mismatches | Mixed sections |",
             "| --- | ---: | ---: | ---: |"]
    for version, value in report["versions"].items():
        audit, headings = value["chapterAudit"], value["headings"]
        detected = headings[f"{version}ReviewedHeadingsDetected"]
        lines.append(f"| {version} | {detected}/7 | {audit['chapterLabelMismatchCount']}/{audit['auditedChunkCount']} | {audit['mixedChapterChunkCount']} |")
    lines += ["", "See private JSON for anchor coverage, cached cosine/BM25 terms and actual hybrid rank paths.",
              "MRR uses >=50% anchor relevance; Recall requires complete anchors. These are not answer-quality scores.",
              "", "## Limitations", "", *[f"- {item}" for item in report["limitations"]]]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        # 6. Refuse collisions before input access; omit raw errors/credentials.
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose new private JSON/Markdown outputs; never overwrite.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_diagnostics(args.pdf_dir, args.baseline))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_report(report))
        print(f"Saved {args.output}; 40 diagnostic pairs; 120 exact replay cases; provider calls=0; HOLD v1.")
        return 0
    except Exception as error:
        print(f"Vietnamese diagnostics failed ({type(error).__name__}); check seals/reference/source/cache. "
              "Raw error omitted; no passing report.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: reject output collision -> sealed labels/owner receipt/completed reference
# -> pinned settings + PDF checksum + real cache only -> exact 120-case replay
# -> actual traced/untraced hybrid invariance + source-span/heading/BM25/cosine
# diagnostics -> recheck immutable inputs -> NEW private reports, HOLD v1.
# Purpose: distinguish missing headings, split evidence and rank/context losses
# without changing the answer labels, model, ranking or deployed database/index.
