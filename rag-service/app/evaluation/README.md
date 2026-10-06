# Evaluation artifacts

`datasets/gift_of_the_magi_v1.json` is the current, source-labeled development
dataset. `datasets/sample_eval_set.json` is preserved from the original scaffold;
it is not accepted by the new runner because it has no scope/source labels.

Run `python -m app.evaluation.run_eval --help` for options. The operational guide
is [retrieval/evaluation implementation](../../docs/retrieval-evaluation-implementation.md).

Reports include failed attempts as well as successful runs. A report with
`errorCount > 0` is not a quality baseline. The first live run on 2026-10-05 hit
the API before startup finished; those artifacts are kept for traceability.
The following successful comparison initially showed improved hit rate but
lower MRR, which motivated the subsequent reranker adjustment.

`rag-eval-20261005T101256Z` is the successful final retrieval-only comparison.
The answer runs `101751Z` and `102837Z` failed due to Gemini rate/day quotas;
they are not passing answer-quality baselines. Use `--llm-interval-seconds`
for RPM and `--case-ids` for an explicitly reported smaller judge subset.
Pacing does not fix an exhausted daily quota.

These questions and expected chunk IDs describe one local sample PDF. Reports
must not be interpreted as a broad benchmark across production ebook content.

The separate [real-PDF chunking comparison](../../docs/chunking/real-book-chunking-benchmark.md)
uses shared page/quote anchors for v1/v2 rather than changing this runtime
golden set. Downloaded PDFs, parsed text and embedding caches stay in ignored
`data/chunking-comparison/`. BM25 is offline; Gemini dense/hybrid is opt-in
and uses an exact cosine in-memory index, never runtime Qdrant/Postgres writes.
The [heading regression fix](../../docs/chunking/heading-detection-regression-fix.md)
repairs v2 chapter boundaries on this corpus; retrieval regressions and missing
held-out/Vietnamese review still keep the default strategy at v1.

[Ranking diagnostics and Vietnamese query holdout](../../docs/chunking/retrieval-diagnostics-and-vi-query-holdout.md)
add source-disjoint new Vietnamese queries on existing English PDFs. This is
query-level/cross-lingual evaluation, not an unseen-book or Vietnamese-source
quality claim. Cache-only diagnostics never call an embedding provider.

[Hybrid ranking trace](../../docs/chunking/hybrid-ranking-trace.md) observes the
real pipeline before/after fusion limits, reranking and thresholding, then adds
source evidence labels after scoring. Opt-in tracing must return exactly the
same response as the normal path; it changes no ranking policy or HTTP schema.

[Fixed hybrid policy comparison](../../docs/chunking/hybrid-policy-comparison.md)
replays baseline reports and compares five preregistered configurations using
cached real vectors. Segment/case regressions reject every candidate in this
experiment; defaults remain unchanged. This is regression analysis, not a new
blind/native-Vietnamese evaluation or a production rollout.

[Lexical-agreement diagnostics](../../docs/chunking/lexical-agreement-diagnostics.md)
explain all 80 existing case-version pairs using exact BM25 terms and cached
cosines, including zero-score evidence rows. Baseline replay and actual branch
score/rank invariants are required. Features are descriptive, not a learned
confidence classifier, title-word filter or new runtime routing/scoring policy.

[Adaptive experiment v2](../../docs/chunking/adaptive-hybrid-experiment-v2.md)
uses an evaluation-only per-query policy hook to test four sealed IDF-strength
formulas with the real pipeline. All have macro gains but fail Magi segment/case
gates; no nominee or runtime adoption. Stop known-case weight tuning and broaden
source/query validation. Public score semantics and v1 manifest/defaults remain unchanged.

[Vietnamese-source first evaluation](../../docs/chunking/vietnamese-source-evaluation-v1.md)
adds a checksum-pinned official Vietnamese translated report, 20 newly frozen
questions / 23 page-quote anchors, and 40 real BM25 cases with exact replay.
It is development/regression data after observation, not native-authored novel
validation or independent blind expert review. The project owner approved all
20 questions / 23 anchors and 7 section headings on 2026-10-06 after first BM25
scoring; the [review sheet](../../docs/chunking/vietnamese-source-review-v1.md)
links a separate hash-bound receipt. Original sealed labels and pre-review
reports remain unchanged. The subsequent
[bounded Gemini baseline](../../docs/chunking/vietnamese-source-embedding-baseline-v1.md)
preserves the first failed attempt (112 submitted inputs, 96 real vectors), then
records a separately approved completion: 120 missing inputs in 37 attempts,
zero errors, all 216 vectors cached and 120 BM25/Dense/Hybrid cases scored.
Hybrid Recall@3 is 92.5%/90% for v1/v2; section and ranking regressions keep HOLD.
No adaptive-formula scoring, runtime change, automatic retry or implicit cost
authorization for another run. Estimated throttle units are not billing tokens.

[Vietnamese baseline diagnosis](../../docs/chunking/vietnamese-baseline-diagnostics-v1.md)
replays the complete 120-case baseline exactly, verifies 40 traced/untraced
Hybrid responses and explains source spans, actual heading detections, BM25
terms and cosine/RRF/reranker ranks. No provider/policy ablation or runtime change.
All 23 anchors have full single-chunk evidence in each chunker; v2 detects only
2/7 reviewed headings. A future section-detection version needs separate guards
and new provider authorization if its embedding inputs change.
