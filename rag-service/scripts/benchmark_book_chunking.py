"""Compare v1/v2 on checksum-pinned real PDFs; BM25 default, Gemini opt-in.

Reports and caches may contain source information: keep them in the ignored
data/chunking-comparison folder. No HTTP download, ingestion job, DB write,
Qdrant upsert, or LLM generation is performed by this command.
"""

from __future__ import annotations

import argparse
import asyncio
from contextlib import redirect_stdout
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version as package_version
import json
from pathlib import Path
import sys

from app.core.config import Settings, get_settings
from app.evaluation.chunking_comparison import (
    build_chunk_result, cosine, evaluate_version, load_dataset, load_holdout_baseline, validate_holdout_anchors,
)
from app.indexing.embedding_text_builder import EmbeddingTextBuilder
from app.ingestion.parsers.base import ParsedDocument, ParserRegistry
from app.ingestion.parsers.pdf.pymupdf4llm_parser import PyMuPDF4LLMParser
from scripts.benchmark_chunking import BASELINE_CONFIG


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "tests/fixtures/chunking/real_books_v1.json"
DEFAULT_PDF_DIR = ROOT / "data/chunking-comparison"


def json_write_new(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")


def parse_pdf(path: Path, checksum: str, *, cache_dir: Path, use_cache=True) -> list[ParsedDocument]:
    """Validate actual PDF bytes even on a cache hit; cache only parsed pages."""
    from unittest.mock import patch
    from app.ingestion.pipeline import IngestionPipeline

    if path.stat().st_size > 30 * 1024 * 1024:
        raise ValueError("Benchmark PDFs must be <=30 MiB.")
    if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise ValueError("PDF checksum differs from reviewed labels. Do not silently relabel.")
    with patch("app.ingestion.pipeline.IngestionArtifactWriter", return_value=None):
        validator = IngestionPipeline(parser_registry=ParserRegistry([]))
    validator._validate_pdf_magic_bytes(path)
    validator._validate_pdf_structure(path, max_pages=500)
    validator._validate_pdf_text_layer(path, sample_pages=5, min_average_chars=50)
    # Automatically invalidate caches when the parser adapter itself changes.
    import inspect
    adapter_hash = hashlib.sha256(inspect.getsource(PyMuPDF4LLMParser).encode()).hexdigest()
    fingerprint = hashlib.sha256((checksum + ":" + package_version("pymupdf4llm")
                                  + ":" + package_version("PyMuPDF") + ":" + adapter_hash).encode()).hexdigest()
    cache = cache_dir / f"parsed-{fingerprint}.json"
    if use_cache and cache.exists():
        payload = json.loads(cache.read_text(encoding="utf-8"))
        if payload.get("fingerprint") != fingerprint:
            raise ValueError("Invalid parser cache fingerprint.")
        parsed = [ParsedDocument(text=page["text"], metadata=page["metadata"]) for page in payload["pages"]]
        if (not parsed or any(not isinstance(page.text, str)
                              or page.metadata.get("page_number") != index
                              for index, page in enumerate(parsed, 1))):
            raise ValueError("Invalid ordered pages in parser cache.")
        return parsed
    parsed = PyMuPDF4LLMParser().parse(path)
    if not parsed or any(page.metadata.get("page_number") != index for index, page in enumerate(parsed, 1)):
        raise ValueError("PDF parser did not return ordered, physical PDF page numbers.")
    if use_cache and not cache.exists():
        json_write_new(cache, {"fingerprint": fingerprint, "pages": [
            {"text": page.text, "metadata": page.metadata} for page in parsed
        ]})
    return parsed


class CachedGemini:
    """Immutable per-input cache; tasks/model/dimension/policy cannot collide."""
    def __init__(self, provider, *, cache_dir: Path, settings: Settings, interval: float):
        self.provider, self.cache_dir, self.settings, self.interval = provider, cache_dir, settings, interval
        self.memory = {}
        self.request_count = 0
        self.cache_hits = 0

    def key(self, task: str, text: str) -> str:
        return hashlib.sha256(json.dumps([
            task, self.settings.embedding_model, self.settings.embedding_dim,
            self.settings.embedding_version, self.settings.embedding_text_policy, text,
        ], ensure_ascii=False).encode()).hexdigest()

    def read(self, task: str, text: str):
        key = self.key(task, text)
        if key in self.memory:
            self.cache_hits += 1
            return self.memory[key]
        path = self.cache_dir / f"embedding-{key}.json"
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
            vector = payload.get("vector")
            if (payload.get("key") != key or not isinstance(vector, list)
                    or len(vector) != self.settings.embedding_dim
                    or any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in vector)):
                raise ValueError("Invalid embedding cache. Do not mix models/dimensions.")
            cosine(vector, vector)  # finite/non-zero validation, not a provider stand-in
            self.memory[key] = vector
            self.cache_hits += 1
            return vector
        return None

    def save(self, task, text, vector):
        if (not isinstance(vector, list) or len(vector) != self.settings.embedding_dim
                or any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in vector)):
            raise ValueError("Embedding dimension mismatch.")
        cosine(vector, vector)
        key = self.key(task, text)
        self.memory[key] = vector
        path = self.cache_dir / f"embedding-{key}.json"
        if not path.exists():
            json_write_new(path, {"key": key, "vector": vector})

    async def _pace(self):
        if self.request_count:
            await asyncio.sleep(self.interval)
        self.request_count += 1

    async def embed_documents(self, texts):
        missing = list(dict.fromkeys(text for text in texts if self.read("document", text) is None))
        for start in range(0, len(missing), 16):
            batch = missing[start:start + 16]
            await self._pace()
            vectors = await self.provider.embed(batch)
            if len(vectors) != len(batch):
                raise ValueError("Embedding count mismatch.")
            for text, vector in zip(batch, vectors, strict=True):
                self.save("document", text, vector)
        return [self.read("document", text) for text in texts]

    async def embed_query(self, text):
        cached = self.read("query", text)
        if cached is not None:
            return cached
        await self._pace()
        vector = await self.provider.embed_query(text)
        self.save("query", text, vector)
        return vector


async def build_report(dataset, pdf_dir: Path, *, gemini=False, interval=2.0,
                       max_embedding_texts=400, context_budget=12000, use_parse_cache=True,
                       embedding_settings=None, provider_factory=None, embedding_cache_factory=None) -> dict:
    local_settings = Settings.model_construct(**BASELINE_CONFIG, keyword_candidate_limit=2000,
                                               hybrid_fail_open=False, context_expansion_window=1)
    baseline_books = load_holdout_baseline(dataset, DEFAULT_DATASET)
    dataset_hash = hashlib.sha256(json.dumps(dataset.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    report = {"schemaVersion": 1, "dataset": {"name": dataset.name, "version": dataset.version,
              "split": dataset.split, "queryLanguage": dataset.query_language, "sourceLanguage": dataset.source_language,
              "reviewStatus": dataset.review_status, "manifestContentSha256": dataset_hash,
              "baselineDatasetSha256": dataset.baseline_dataset_sha256},
              "generatedAt": datetime.now(timezone.utc).isoformat(), "config": {
                  "chunkSize": 512, "chunkOverlap": 64, "topK": [1, 3, 5], "contextSeedK": 3,
                  "contextWindow": 1, "contextBudgetCharacters": context_budget,
                  "sameParserAndCleanedText": True,
              }, "runtimeWrites": False, "llmGeneration": False, "books": [], "errorCount": 0,
              "limitations": [f"{len(dataset.books)} source document(s), source language={dataset.source_language}, "
                              f"query language={dataset.query_language}; "
                              + ("query-level holdout is not unseen-document validation."
                                 if dataset.split == "query_holdout" else "development data, no held-out quality claim."),
                              "Exact cosine in memory is not a live Qdrant/Postgres benchmark.",
                              "Answer faithfulness, abstention and production latency are not evaluated."]}
    prepared = []
    for document_id, book in enumerate(dataset.books, 900001):
        print(f"Parsing/validating {book.id} ...", file=sys.stderr, flush=True)
        parsed = parse_pdf(pdf_dir / book.filename, book.sha256, cache_dir=pdf_dir / "cache", use_cache=use_parse_cache)
        versions = {version: build_chunk_result(parsed, book, version=version, document_id=document_id)
                    for version in ("v1", "v2")}
        if baseline_books:
            validate_holdout_anchors(book, baseline_books[book.id], {
                page.metadata["page_number"]: page.text for page in versions["v1"].cleaned_documents
            })
        entry = {"id": book.id, "sourceUrl": book.source_url, "sha256": book.sha256,
                 "physicalPdfPageCount": len(parsed), "questionCount": len(book.questions), "versions": {}}
        for version, result in versions.items():
            entry["versions"][version] = await evaluate_version(book, result, document_id=document_id,
                                                              modes=("bm25",), settings=local_settings,
                                                              context_budget=context_budget)
        if entry["versions"]["v1"]["cleanedPageHashes"] != entry["versions"]["v2"]["cleanedPageHashes"]:
            raise ValueError("Unfair comparison: the versions did not use identical cleaned pages.")
        prepared.append((book, document_id, versions, entry))
        report["books"].append(entry)

    if gemini:
        # Runtime Settings are read ONLY for the explicitly requested provider.
        # Never serialize Settings or raw SDK errors: either can expose secrets.
        from app.indexing.providers import GeminiEmbeddingProvider
        runtime = None
        if embedding_settings is None:
            runtime = get_settings()
            settings = local_settings.model_copy(update={
                "embedding_model": runtime.embedding_model, "embedding_dim": runtime.embedding_dim,
                "embedding_version": runtime.embedding_version, "embedding_text_policy": runtime.embedding_text_policy,
            })
        else:
            # An offline, preregistered runner may pin public settings and supply
            # a bounded provider. Cache-only replay must not need .env access.
            settings = embedding_settings
        builder = EmbeddingTextBuilder(settings=settings)
        inputs = {("document", builder.build_document_text(chunk.text, chunk.metadata).text)
                  for _, _, versions, _ in prepared for result in versions.values() for chunk in result.chunks}
        inputs.update(("query", builder.build_query_text(question.question).text)
                      for book, _, _, _ in prepared for question in book.questions)
        report["embedding"] = {"provider": "gemini", "model": settings.embedding_model,
                               "dimension": settings.embedding_dim, "version": settings.embedding_version,
                               "textPolicy": settings.embedding_text_policy, "uniqueInputCount": len(inputs),
                               "maxEmbeddingTexts": max_embedding_texts}
        cached = None
        try:
            if len(inputs) > max_embedding_texts:
                raise ValueError("Explicit embedding input budget exceeded before any provider call.")
            provider = (provider_factory(settings=settings) if provider_factory is not None else
                        GeminiEmbeddingProvider(settings=runtime or get_settings(), batch_size=16, max_retries=0))
            cache_factory = embedding_cache_factory or CachedGemini
            cached = cache_factory(provider, cache_dir=pdf_dir / "cache", settings=settings, interval=interval)
            for book, document_id, versions, entry in prepared:
                for version, result in versions.items():
                    print(f"Embedding/evaluating {book.id}/{version} ...", file=sys.stderr, flush=True)
                    texts = [builder.build_document_text(chunk.text, chunk.metadata).text for chunk in result.chunks]
                    vectors = await cached.embed_documents(texts)
                    measured = await evaluate_version(book, result, document_id=document_id,
                                                     modes=("dense", "hybrid"), settings=settings, vectors=vectors,
                                                     query_provider=cached, context_budget=context_budget)
                    entry["versions"][version]["retrieval"].update(measured["retrieval"])
            report["embedding"].update(status="passed", requestCount=cached.request_count, cacheHits=cached.cache_hits)
        except Exception as error:
            report["errorCount"] += 1
            report["embedding"].update(status="failed", errorType=type(error).__name__,
                                      errorCategory=provider_error_category(error),
                                      note="Not a passing dense/hybrid baseline. Check quota/key/network or input budget; raw error omitted.")
        if cached is not None:
            report["embedding"].update(requestCount=cached.request_count, cacheHits=cached.cache_hits)
    regressions = []
    for entry in report["books"]:
        first, second = entry["versions"]["v1"], entry["versions"]["v2"]
        for mode in first["retrieval"].keys() & second["retrieval"].keys():
            old, new = first["retrieval"][mode], second["retrieval"][mode]
            if (new["metricsAtK"]["3"]["evidenceRecall"] < old["metricsAtK"]["3"]["evidenceRecall"]
                    or new["metricsAtK"]["5"]["reciprocalRank"] < old["metricsAtK"]["5"]["reciprocalRank"]
                    or new["contextRetainedAnchorRate"] < old["contextRetainedAnchorRate"]):
                regressions.append({"book": entry["id"], "mode": mode})
    report["decision"] = {
        "defaultStrategy": "v1", "automaticPromotion": False,
        "gates": {
            "sourceCoverage": all(entry["versions"]["v2"]["nonWhitespaceSourceCoverage"] == 1 for entry in report["books"]),
            "chapterLabels": all(entry["versions"]["v2"]["chapterAudit"]["chapterLabelMismatchCount"] == 0 for entry in report["books"]),
            "denseHybridCompleted": report.get("embedding", {}).get("status") == "passed",
            "noMeasuredRegression": not regressions,
            "heldOutVietnameseReviewed": False,
        },
        "regressions": regressions,
        "recommendation": "HOLD: review per-book regressions/chapter mismatches; add Vietnamese and held-out data before promoting v2.",
    }
    return report


def provider_error_category(error: Exception) -> str:
    """Classify diagnostics without serializing raw SDK messages or keys."""
    message = str(error).casefold()
    if any(marker in message for marker in ("429", "resource_exhausted", "quota", "rate limit")):
        return "rate_or_quota"
    if any(marker in message for marker in ("401", "403", "api key", "permission")):
        return "authentication_or_permission"
    if any(marker in message for marker in ("timeout", "503", "500", "connect")):
        return "network_or_provider_unavailable"
    if "budget" in message:
        return "input_budget"
    if any(marker in message for marker in ("400", "invalid_argument", "dimension", "count mismatch")):
        return "invalid_input_or_response"
    return "unclassified"


def markdown_report(report) -> str:
    lines = ["# Real-PDF chunking comparison", "", f"Generated: {report['generatedAt']}", "",
             f"Errors: {report['errorCount']}. Runtime writes: false. LLM generation: false.", "",
             "| Book | Strategy | Chunks | Chapter mismatches | Source coverage | Mode | Evidence Recall@3 | MRR@5 | Context retained |",
             "| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |"]
    for book in report["books"]:
        for version, measured in book["versions"].items():
            for mode, metrics in measured["retrieval"].items():
                lines.append(f"| {book['id']} | {version} | {measured['chunkCount']} | "
                             f"{measured['chapterAudit']['chapterLabelMismatchCount']} | "
                             f"{measured['nonWhitespaceSourceCoverage']:.3f} | {mode} | "
                             f"{metrics['metricsAtK']['3']['evidenceRecall']:.3f} | "
                             f"{metrics['metricsAtK']['5']['reciprocalRank']:.3f} | {metrics['contextRetainedAnchorRate']:.3f} |")
    lines += ["", "## Decision", "", report["decision"]["recommendation"], "", "## Limitations", ""]
    lines += [f"- {text}" for text in report["limitations"]]
    if report.get("embedding", {}).get("status") == "failed":
        lines += ["", "Gemini evaluation FAILED: do not use missing/partial modes as quality evidence."]
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--output", type=Path, required=True, help="New JSON file; a matching .md is also created.")
    parser.add_argument("--gemini", action="store_true", help="Opt in to real Gemini embeddings (quota/cost).")
    parser.add_argument("--interval-seconds", type=float, default=2.0)
    parser.add_argument("--max-embedding-texts", type=int, default=400)
    parser.add_argument("--context-budget", type=int, default=12000)
    parser.add_argument("--no-parse-cache", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose a new .json path; neither output may already exist.")
        if not 0 <= args.interval_seconds <= 60 or not 1 <= args.max_embedding_texts <= 1000 or args.context_budget <= 0:
            raise ValueError("Invalid pacing/budget.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_report(load_dataset(args.dataset), args.pdf_dir, gemini=args.gemini,
                                             interval=args.interval_seconds, max_embedding_texts=args.max_embedding_texts,
                                             context_budget=args.context_budget, use_parse_cache=not args.no_parse_cache))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_report(report))
        print(markdown_report(report))
        return 1 if report["errorCount"] else 0
    except Exception as error:
        print(f"Comparison failed ({type(error).__name__}); check labels/PDF checksum or output/config. "
              "No valid comparison report was produced.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: load reviewed labels -> validate/checksum/parse real local PDFs -> run
# both chunkers on identical pages -> BM25/source/context audit -> optionally
# real Gemini + exact cosine/hybrid with bounded, reusable caches -> NEW JSON/MD.
# Purpose: make a measured promotion decision without overwriting existing
# reports, relabeling chunk IDs, reindexing production, or exposing secrets.
