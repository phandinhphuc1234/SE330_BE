# PostgreSQL 16 production retrieval baseline - 2026-10-25

Status: **DEV RETRIEVAL BASELINE COMPLETE; ANSWER / HELD-OUT / READER GATES PENDING**.

The measurement ran on 2026-10-25. This report was consolidated on 2026-10-28;
it is not a new run or a statement about current VPS health.

## Provenance and safety

- Separate [PR #40](https://github.com/phandinhphuc1234/SE330_BE/pull/40),
  executed helper commit `139d1b50613e9857d3e2a898d46de0ac56387dca`.
- Exact-helper-commit [CI run 40188491366](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40188491366)
  passed: **325 Java tests**, **860 RAG tests**, CodeQL and SonarCloud.
  RAG app coverage was **87%**. Image build/publish and production deployment
  jobs were skipped. CodeRabbit's successful status indicated a skipped review,
  not independent review approval.
- [Retrieval run 40188794204](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40188794204)
  completed successfully using `mode=pg16-retrieval-dev`, `document_id=2`.
- Corpus scope: book **3343**, ebook **11**, document **2**, ingestion job **2**.
  PDF SHA-256:
  `73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26`.
- Frozen question-spec SHA-256:
  `22930554efe7a7fa5a70a28683ad75c5fe6dd133991c647cedc78c99688addb2`.
- Existing immutable runtime image:
  `ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e`.
  Embedding version: `gemini-embedding-2-3072-v1`; chunking version: `v1`.
- Read-only Qdrant preflight verified **35/35 expected points**, including content
  hashes, book/ebook/document scope, source hash, active state, versions and page
  provenance. This is stronger than checking nonempty vector IDs in SQL alone.
- The temporary helper did not replace installed app files. No merge, build,
  deployment, service restart, reindex, new port, entitlement or source/index
  content change was performed by this benchmark. Searches can populate normal
  caches/counters and consume query-embedding quota.

The raw report's `code.commit` is null because the installed production image
contains no Git checkout. Its `runtime.helperCommit` and `runtime.runtimeImage`
record the executed helper and runtime identities explicitly; null is not filled
with an invented application Git revision.

## Protocol and denominators

- One technical corpus: 22-page PostgreSQL 16 Chapter 13, **35 indexed chunks**.
- Forty source-labelled English questions were frozen before retrieval: **30 dev
  / 10 held-out**. Labels are assistant source-checked, not independently
  human-reviewed. This is same-document question holdout, not unseen-document
  generalization.
- Exactly **30 sequential dense requests, then 30 hybrid requests**, `topK=3`.
  The selected dev split contains **27 answerable and 3 unanswerable questions**.
- Relevance metrics below are macroaverages over **27 answerable dev questions**.
  Latencies use **30 requests per mode**, including the three negative searches.
  Returning passages for a negative search is not an abstention test.
- No answer generation, LLM judge, held-out query or answer-quality measurement.
  No retrieval/chunk/prompt tuning or post-run label changes were used to improve
  the reported baseline.

## Observed results

| Metric | Dense | Hybrid |
| --- | ---: | ---: |
| HitRate@3 | 96.2963% (26/27) | 100% (27/27) |
| Chunk Recall@3 | 93.2099% | **98.7654%** |
| Precision@3 | 41.9753% | 44.4444% |
| MRR | 0.944444 | **1.000000** |
| nDCG@3 | 0.919670 | 0.979416 |
| Evidence-group coverage@3 | 93.2099% | 98.7654% |
| Mean retrieval latency, N=30 | 537.5 ms | 618.9 ms |
| Observed p50, nearest rank, N=30 | 512.2 ms | 619.8 ms |
| Observed p95, nearest rank, N=30 | 655.5 ms | 675.6 ms |
| Request errors | 0 | 0 |
| Cross-scope violations | 0 | 0 |

Hybrid's observed mean chunk Recall@3 difference is **+5.56 percentage points**
on this fixed development set. This is not a statistically established gain on
unseen data. HitRate 100% means at least one labelled relevant chunk was found
in the top three for every answerable dev query, not that every returned chunk
or generated answer was correct. MRR 1.0 means the first result was labelled
relevant for these 27 queries.

Evidence-group coverage treats overlapping alternatives as one required excerpt;
chunk recall counts their union. They happen to have equal aggregate values in
this run but remain different metrics. Some full-corpus union labels contain four
chunks, so perfect chunk Recall@3 is not possible for every question.

## Retained misses and limitations

- Dense completely missed `pg-f15` (strictest supported hot-standby isolation
  level). It partially covered `pg-m07` (2/3) and `pg-m10` (1/2).
- Hybrid's remaining partial case was **`pg-m07`**, recall **2/3**. Expected chunk
  indices were **9, 29, 30**; both modes returned **30, 29, 27**. The Repeatable
  Read snapshot passage in chunk 9 was not returned. The original labels and
  failure are retained, not relabelled after measurement to obtain 100% recall.
- `operationGate.passed=true` covers execution/scope checks;
  `semanticReleaseGate.passed=null` deliberately declares no semantic release
  threshold for baseline collection. Workflow success is not release acceptance.
- Dense ran first and hybrid second; cache-order effects were not controlled.
  These are internal-VPS sequential observations, not browser end-to-end timing,
  cold/warm comparisons, concurrent capacity, an SLA or LLM response latency.
- Zero observed scope violations is limited to these scoped searches, not proof
  of every authorization boundary. Citation metadata counters without generated
  answers do not establish citation entailment or answer faithfulness.
- The production private-PDF origin/reader blocker remains unresolved by this
  benchmark. Prior Magi answer/quota failures cannot be transferred to or erased
  by the PostgreSQL retrieval result.

## Preserved raw evidence

The following files are copied unchanged from the workflow artifact:

- [Runtime inventory](pg16-production-20261025/retrieval-dev-40188794204/inventory.json).
- [Bound dev dataset](pg16-production-20261025/retrieval-dev-40188794204/bound-dev.json).
- [Alternative evidence groups](pg16-production-20261025/retrieval-dev-40188794204/evidence-groups.json).
- [Qdrant preflight](pg16-production-20261025/retrieval-dev-40188794204/qdrant-preflight.json).
- [Full machine-readable results](pg16-production-20261025/retrieval-dev-40188794204/rag-eval-20261025T112614711467Z.json).
- [Runner summary](pg16-production-20261025/retrieval-dev-40188794204/rag-eval-20261025T112614711467Z.md).

## Truthful CV wording and next gates

Suggested wording, not an edit to a CV file:

> Evaluated hybrid RAG retrieval on a 22-page PostgreSQL technical corpus (35
> indexed chunks), achieving 98.8% Recall@3 and MRR 1.0 across 27 answerable
> development queries; observed p95 retrieval latency of 676 ms over 30 sequential
> internal-VPS requests, with no cross-ebook scope violations in that run.

Keep **development**, the sample size and **retrieval** in any shortened claim.
Do not call this answer accuracy or a production latency guarantee.

Remaining gates require separate approval where applicable: independent label
review, a frozen-candidate held-out pass, provider-ready complete answer and
abstention evaluation, and authenticated public reader/citation checks. This PR
and benchmark approval alone do not authorize merge or deployment.
