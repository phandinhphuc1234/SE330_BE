"""Owner-reviewed Vietnamese regression baseline; bounded Gemini or cache-only.

Only --gemini permits provider calls. Cap: 220 submitted embedding inputs,
including failed attempts; no automatic retries. No LLM, upload, DB/Qdrant
writes, policy tuning or promotion. Preserve historical labels and reports.
"""

import argparse
import asyncio
from contextlib import redirect_stdout
import math
from pathlib import Path
import sys

from app.evaluation.vietnamese_source_validation import (
    CONTEXT_BUDGET, DATASET_PATH, EMBEDDING_IDENTITY, OWNER_REVIEW_PATH,
    inspect_cache, registration_manifest, verify_owner_review, verify_registration,
)
from scripts.benchmark_book_chunking import (
    CachedGemini, DEFAULT_PDF_DIR, build_report, json_write_new, markdown_report,
)
from scripts.benchmark_vietnamese_source import collect_embedding_inputs
from scripts.embedding_throttle import (
    DOCUMENT_BATCH_SIZE, DOCUMENT_BATCH_UNITS, TokenWindowLimiter, document_batches, estimate_units,
)


MAX_AUTHORIZED_INPUTS = 220
BASELINE_RANKING = {
    "hybrid_candidate_multiplier": 3, "hybrid_rrf_k": 60,
    "hybrid_vector_weight": .7, "hybrid_keyword_weight": .3,
    "hybrid_fail_open": False, "keyword_candidate_limit": 2000,
    "query_rewrite_max_queries": 2, "context_expansion_window": 1,
}
BASELINE_RERANKER = {"rrf": .4, "cosine": .5, "lexical": .1}


class BoundedProvider:
    """Reserve each submission BEFORE I/O, including failures, with no retry.

    Only exact preflight task/text pairs are allowed. A repeated submission is
    rejected even after a failed response; CachedGemini handles valid reuse.
    Counters do not contain source/query text, API keys or raw SDK exceptions.
    """

    def __init__(self, provider, *, allowed_inputs, max_inputs, limiter=None):
        self.provider = provider
        self.allowed_inputs = frozenset(allowed_inputs)
        self.max_inputs = max_inputs
        self.submitted = set()
        self.request_count = 0
        self.successful_inputs = 0
        self.limiter = limiter

    def _validate(self, task, texts):
        pairs = [(task, text) for text in texts]
        if (not pairs or len(set(pairs)) != len(pairs)
                or any(pair not in self.allowed_inputs or pair in self.submitted for pair in pairs)):
            raise ValueError("Embedding input is unregistered or already submitted; no retries allowed.")
        if self.provider is None or len(self.submitted) + len(pairs) > self.max_inputs:
            raise ValueError("Explicit provider input budget exceeded before I/O.")
        return pairs

    def _reserve(self, task, texts):
        pairs = self._validate(task, texts)
        self.submitted.update(pairs)
        self.request_count += 1

    async def embed(self, texts):
        self._validate("document", texts)
        if self.limiter is not None:
            if len(texts) > DOCUMENT_BATCH_SIZE:
                raise ValueError("Document batch exceeds the input limit before I/O.")
            await self.limiter.acquire(sum(estimate_units(text) for text in texts))
        self._reserve("document", texts)
        vectors = await self.provider.embed(texts)
        self.successful_inputs += len(texts)
        return vectors

    async def embed_query(self, text):
        self._validate("query", [text])
        if self.limiter is not None:
            await self.limiter.acquire(estimate_units(text))
        self._reserve("query", [text])
        vector = await self.provider.embed_query(text)
        self.successful_inputs += 1
        return vector

    def counters(self):
        return {"providerRequestAttempts": self.request_count,
                "submittedInputCount": len(self.submitted),
                "successfulResponseInputCount": self.successful_inputs,
                "byTaskSubmitted": {task: sum(pair[0] == task for pair in self.submitted)
                                    for task in ("document", "query")}}


class TokenPacedCache(CachedGemini):
    """Same immutable cache identity; cache every small successful batch.

    Splitting at the cache layer means a later failed batch cannot discard
    vectors already received for an earlier small batch.
    """

    async def embed_documents(self, texts):
        missing = list(dict.fromkeys(text for text in texts if self.read("document", text) is None))
        for batch in document_batches(missing):
            await super().embed_documents(batch)
        return [self.read("document", text) for text in texts]


def create_real_provider(settings):
    # 1. Read only the user's existing key; public model/config come from the
    # sealed identity, not mutable .env settings. Never serialize credentials.
    from app.core.config import get_settings
    from app.indexing.providers import GeminiEmbeddingProvider
    from google import genai
    from google.genai import types

    api_key = get_settings().gemini_api_key
    if not api_key:
        raise ValueError("Gemini API key is missing.")
    # Disable BOTH application retries and SDK retries. Timeout is milliseconds.
    client = genai.Client(api_key=api_key, vertexai=False, http_options=types.HttpOptions(
        timeout=30000, retry_options=types.HttpRetryOptions(attempts=1)))
    provider = GeminiEmbeddingProvider(settings=settings, client=client, types_module=types,
                                       batch_size=DOCUMENT_BATCH_SIZE, max_retries=0)
    return provider, client


async def build_embedding_report(pdf_dir: Path, *, gemini=False, max_inputs=MAX_AUTHORIZED_INPUTS,
                                 interval=2.0, dataset_path=DATASET_PATH, review_path=OWNER_REVIEW_PATH):
    # 2. Validate budget/approval/seals/source/anchors/cache before constructing
    # any network client. --cache-only has no .env or provider fallback.
    if (type(max_inputs) is not int or not 1 <= max_inputs <= MAX_AUTHORIZED_INPUTS
            or not math.isfinite(interval) or not 0 <= interval <= 60):
        raise ValueError("Require an input cap <=220 and finite pacing between 0 and 60 seconds.")
    dataset = verify_registration(dataset_path)
    review = verify_owner_review(dataset, review_path)
    first_registration = registration_manifest()
    from app.retrieval.reranker import Reranker

    settings = EMBEDDING_IDENTITY.settings().model_copy(update=BASELINE_RANKING)
    if Reranker().weights != BASELINE_RERANKER:
        raise ValueError("Baseline reranker drifted; register a new experiment instead.")
    inputs = collect_embedding_inputs(dataset, pdf_dir)
    preflight = inspect_cache(inputs, CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0))
    if len(inputs) > MAX_AUTHORIZED_INPUTS:
        raise ValueError("Registered corpus input budget exceeded before any provider call.")
    if gemini and preflight["missingInputCount"] > max_inputs:
        raise ValueError("Missing embedding inputs exceed the explicit submission budget before I/O.")
    if not gemini and preflight["missingInputCount"]:
        raise ValueError("Real embedding cache is incomplete; cache-only replay cannot call providers.")
    if any(estimate_units(text) > DOCUMENT_BATCH_UNITS for _, text in inputs):
        raise ValueError("Embedding input exceeds the registered safe token reservation.")

    client = None
    limiter = TokenWindowLimiter()
    bounded = BoundedProvider(None, allowed_inputs=inputs, max_inputs=max_inputs if gemini else 0, limiter=limiter)

    def provider_factory(*, settings):
        nonlocal client
        if gemini and preflight["missingInputCount"]:
            bounded.provider, client = create_real_provider(settings)
        return bounded

    # 3. Reuse the real baseline pipeline: BM25 + dense exact cosine + hybrid
    # fusion/reranking, same v1/v2 source and context budget. No experimental
    # weights or generation. Cache each successful vector for zero-cost replay.
    try:
        report = await build_report(dataset, pdf_dir, gemini=True, interval=interval,
                                    max_embedding_texts=MAX_AUTHORIZED_INPUTS, context_budget=CONTEXT_BUDGET,
                                    embedding_settings=settings, provider_factory=provider_factory,
                                    embedding_cache_factory=TokenPacedCache)
    finally:
        if client is not None:
            client.close()

    postflight = inspect_cache(inputs, CachedGemini(None, cache_dir=pdf_dir / "cache", settings=settings, interval=0))
    report.update(stage="vietnamese-source-owner-reviewed-embedding-baseline-v1",
                  firstScoringRegistration=first_registration, ownerReview=review,
                  automaticPromotion=False, embeddingPreflight=preflight, embeddingPostflight=postflight,
                  providerCalls=bounded.request_count,
                  providerAuthorization={"mode": "explicit_gemini_opt_in" if gemini else "cache_only",
                                         "maxSubmittedInputs": max_inputs if gemini else 0,
                                         "applicationRetries": 0, "sdkAttemptsPerRequest": 1,
                                         "runtimeWrites": False, "llmGeneration": False})
    report["embedding"].update(bounded.counters())
    report["embeddingThrottle"] = limiter.report()
    report["config"]["ranking"] = dict(BASELINE_RANKING)
    report["config"]["rerankerWeights"] = dict(BASELINE_RERANKER)
    report["embedding"]["initialCachedInputCount"] = len(inputs) - preflight["missingInputCount"]
    report["embedding"]["finalCachedInputCount"] = len(inputs) - postflight["missingInputCount"]
    report["embedding"]["billingTokensMeasured"] = False
    report["embedding"]["billingCostMeasured"] = False
    report["caseCountByMode"] = {mode: sum(version["retrieval"].get(mode, {}).get("caseCount", 0)
                                         for book in report["books"] for version in book["versions"].values())
                                 for mode in ("bm25", "dense", "hybrid")}
    report["caseCount"] = sum(report["caseCountByMode"].values())
    expected_cases = 2 * sum(len(book.questions) for book in dataset.books)
    completed = (report["embedding"]["status"] == "passed" and postflight["status"] == "ready"
                 and all(count == expected_cases for count in report["caseCountByMode"].values()))
    report["decision"]["gates"].update(ownerReviewCompleted=True, humanReviewCompleted=True,
                                        independentBlindExpertReview=False, realEmbeddingCacheReady=postflight["status"] == "ready",
                                        denseHybridCompleted=completed)
    report["decision"]["recommendation"] = (
        "HOLD v1 and baseline ranking. Diagnose baseline retrieval failures and section boundary gaps; "
        "no tuning on observed cases, policy nomination or automatic promotion.")
    if not completed and report["errorCount"] == 0:
        report["errorCount"] = 1
    report["limitations"] += [
        "Owner review occurred after first BM25 scoring; not independent blind expert review or new unseen holdout.",
        "One official translated Vietnamese nonfiction report; not native-authored novel or broad corpus validation.",
        "Positive evidence questions only; no negative/abstention cases, answer generation or LLM faithfulness judge.",
        "Submitted inputs and request attempts are measured; billing tokens/cost and production latency are not.",
        "Fixed/adaptive experimental policies are sealed but not scored here; ranking and chunker defaults are unchanged.",
        "TPM admission uses conservative local UTF-8 reservations, not Gemini's exact tokenizer/billing or other clients' shared-project traffic.",
    ]
    # 4. Recheck exact registration AND owner receipt after scoring; never make
    # a valid report for data/authority changed while this run was in progress.
    verify_registration(dataset_path)
    if (registration_manifest() != first_registration or verify_owner_review(dataset, review_path) != review
            or Reranker().weights != BASELINE_RERANKER):
        raise ValueError("Registration or owner-review receipt changed during scoring.")
    return report


def markdown_summary(report):
    embedding = report["embedding"]
    return markdown_report(report) + (
        "\n## Review and bounded provider run\n\n"
        f"Owner review: approved after first BM25 scoring. Receipt SHA256: `{report['ownerReview']['receiptSha256']}`.\n"
        "Not independent blind review. Original first-scoring registration stays pending as historical provenance.\n\n"
        f"Mode: {report['providerAuthorization']['mode']}. Cases by mode: {report['caseCountByMode']}.\n"
        f"Unique inputs: {embedding['uniqueInputCount']}; submitted: {embedding['submittedInputCount']}; "
        f"request attempts: {embedding['providerRequestAttempts']}.\n"
        f"Real cache: {embedding['initialCachedInputCount']} before, {embedding['finalCachedInputCount']} after.\n"
        f"Token throttle: {report['embeddingThrottle']['estimator']}; "
        f"peak reserved units={report['embeddingThrottle']['maxWindowReservedUnits']}; exact Gemini tokens=false.\n"
        "No automatic retries, billing-cost/token measurement, runtime writes or LLM generation.\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--gemini", action="store_true", help="Explicit cost/quota opt-in, cap <=220 inputs.")
    mode.add_argument("--cache-only", action="store_true", help="Real-cache replay; no .env/provider access.")
    parser.add_argument("--pdf-dir", type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument("--output", type=Path, required=True, help="NEW private JSON and Markdown; no overwrite.")
    parser.add_argument("--max-embedding-inputs", type=int, default=MAX_AUTHORIZED_INPUTS,
                        help="Cap submitted missing inputs in THIS run, including failed attempts; max220.")
    parser.add_argument("--interval-seconds", type=float, default=2.0)
    args = parser.parse_args(argv)
    try:
        # 5. Refuse collisions before input/API access; keep keys and raw SDK
        # errors out of logs. Write new private artifacts, including failures.
        if args.output.suffix != ".json" or args.output.exists() or args.output.with_suffix(".md").exists():
            raise ValueError("Choose unused JSON/Markdown output paths.")
        with redirect_stdout(sys.stderr):
            report = asyncio.run(build_embedding_report(args.pdf_dir, gemini=args.gemini,
                                                        max_inputs=args.max_embedding_inputs,
                                                        interval=args.interval_seconds))
        json_write_new(args.output, report)
        with args.output.with_suffix(".md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(markdown_summary(report))
        print(f"Saved {args.output}; cases={report['caseCount']}; "
              f"submitted inputs={report['embedding']['submittedInputCount']}; "
              f"provider calls={report['providerCalls']}; errors={report['errorCount']}; HOLD v1.")
        return 0 if report["errorCount"] == 0 else 1
    except Exception as error:
        print(f"Vietnamese embedding evaluation failed ({type(error).__name__}); "
              "check approval/seals/source/cache/budget. Raw error omitted; no passing report.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: explicit opt-in or cache-only -> budget/review/seal/anchor gates ->
# locked embedding identity -> local rolling token throttle + small batches
# -> bounded no-retry provider -> immutable real
# cache -> BM25/Dense/Hybrid baseline -> recheck seals/review -> NEW reports.
# Purpose: measure actual Vietnamese retrieval and reproduce it without paid
# calls, while preserving old evidence and changing no production component.
