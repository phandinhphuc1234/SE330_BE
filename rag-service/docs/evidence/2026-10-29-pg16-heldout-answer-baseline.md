# PostgreSQL frozen-candidate held-out answer baseline - 2026-10-29

Status: **complete 10-case operation/contract PASS**. This is not a claim of
100% semantic accuracy, independent human validation, unseen-document
generalization, load capacity or production SLA.

## Runtime and immutable protocol

- Healthy release: [40698306283 attempt 2](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40698306283),
  deployed source `d94f24e3fec8cb3d624663cd12b23a4ce9cf8f9a`.
- RAG image: `ghcr.io/phandinhphuc1234/se330-rag@sha256:3f978ac7617e80895abb62635fe598af51ce24172560c33e6d3955bb37d62197`.
- Exact CI-approved evaluation helper: `680cc6688369fe866b39c0b91e9450cb31be38d3`, PR #40.
- Provider-free plan: [40805217254](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40805217254),
  candidate `2ac6cc93f707122e8370ddb09fe82490ca4cc2c16a4fa318fd37b3c4d858785f`.
  The source checksum and all 35 Qdrant payloads passed before generation.
- Gemini 2.5 Flash, prompt `library-ebook-answer-v2`, temperature 0.1,
  hybrid retrieval, topK=3, 30-second pacing, no LLM judge or configuration tuning.
  Full allowlisted settings are retained in the raw candidate manifest.
- Source: 22-page PostgreSQL 16 Chapter 13 excerpt, book 3343 / ebook 11 / document 2,
  SHA-256 `73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26`.
- Frozen question specification SHA-256:
  `22930554efe7a7fa5a70a28683ad75c5fe6dd133991c647cedc78c99688addb2`.
  All 10 held-out cases ran once; no subset stitching, relabeling or retries to
  improve the result. The separate 30-case development answer batch has not run.

## Executed result

[Held-out run 40806368432](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40806368432)
completed 02:26-02:31 UTC / 09:26-09:31 GMT+7. It contains nine answerable questions
and one insufficient-information question. All ten responses remain in reports.

| Observation | Result | Denominator/meaning |
| --- | ---: | --- |
| Completed answers | 10/10 | All selected held-out cases |
| Request/provider errors | 0 | 10 cases; no rate-limit/timeout failure observed |
| Scope violations | 0 | Reported retrieval and answer citations |
| Citation metadata/page violations | 0 | All citations within pages 1-22 and correct scope |
| HitRate@3 | 9/9 | Nine answerable cases, not ten |
| Chunk Recall@3 | 91.67% | Mean expected-chunk coverage across nine answerable cases |
| MRR | 0.944444 | Nine answerable cases |
| Evidence-group coverage@3 | 94.44% | Alternative overlapping chunks treated as groups |
| Citation precision / recall | 88.89% / 94.44% | Expected-chunk-label metrics, not claim entailment |
| Lexical answer-point coverage | 96.30% | Keyword diagnostic, not semantic accuracy |
| Expected abstention decision | 10/10 | Nine answers + one abstention; only one negative sample |
| Internal Ask p50 / p95 | 2.863s / 5.720s | Ten sequential requests, includes retrieval/generation |

The contract gate checks completion, scope, grounded/citation structure, page
bounds and the expected abstention decision. It passed with zero failures.
The raw `semanticCorrectnessGate.passed` intentionally remains `null`.
Search runs before Ask in each case, so caches can be warm; these latency
samples are not browser end-to-end timings or concurrent-load measurements.

## Source-entailment review (single assistant, not independent human review)

Raw answer text and citation metadata were inspected against the unchanged full
fixture chunks, not just the shortened excerpts displayed by the UI. No prompt,
ranking or label was changed after inspecting the held-out responses.

| Case | Cited PDF pages | Review note |
| --- | --- | --- |
| pg-f04 | 5 | Sequence changes remain visible and are not rolled back: directly supported. |
| pg-f08 | 11, 15 | pg_locks is supported on page 11; the extra advisory-lock citation on 15 is related but redundant against the narrow label. |
| pg-f12 | 19 | Deadlock SQLSTATE 40P01/deadlock_detected is directly supported. |
| pg-f16 | 21 | B-tree recommendation, performance and feature comparison are supported. |
| pg-f20 | 14 | Page locks release after row fetch/update: directly supported. |
| pg-m03 | 5, 7 | WHERE recheck versus Repeatable Read serialization failure/retry is supported; one broad context citation is unnecessary. |
| pg-m08 | 17 | Core no/equivalence claim is supported. Explanation is less complete than the reference: it omits the maximum standby level and transient inconsistent-state detail from page 20. |
| pg-s02 | 13 | ROW EXCLUSIVE conflicts with SHARE, not ACCESS SHARE: matches Table 13.2 cells. |
| pg-s05 | 8 | One Serializable transaction rolls back; A's inserted 30 makes B's sum 330: source and arithmetic match. |
| pg-u04 | none | Correctly abstains on Azure bill/latency, which the corpus cannot establish. |

All nine answerable responses have source-consistent core answers. This review
does not turn the run into a validated semantic-accuracy percentage; pg-m08 is
explicitly incomplete relative to its reference explanation. Abstention uses a
fixed Vietnamese message even for an English query, a language/UX limitation.

## Public-reader smoke (separate from the held-out denominator)

Using an existing MEMBER loan on the deployed website:

- The unchanged PostgreSQL PDF rendered with 22 pages.
- Development question pg-f02 returned Read Committed and a page-5 citation;
  clicking it changed the reader to 5/22, whose visible text supports the answer.
- Development negative pg-u02 (Redis Lua/TTL) displayed insufficient evidence,
  without presenting citations. These two UI calls are not stitched into a
  complete development answer benchmark.
- Anonymous PDF GET, HEAD and Range each returned 401 from the public API.
- Authenticated file Range/HEAD, missing/foreign/closed-session negative checks
  remain **not executed in production**; their CI tests are not live evidence.

## Raw provenance and limitations

Byte-preserved reports, candidate, per-attempt responses, bound dataset, Qdrant
preflight and evidence groups are in
[heldout-40806368432](pg16-production-20261029/heldout-40806368432/).
Generated reports have no Git checkout in the runtime (`code.commit=null`);
the separately recorded immutable image and helper manifest supply provenance.
Private backups/S3 object-key manifests and browser authentication are not copied
into these reports. Source licensing remains with the versioned corpus fixture.

This is a small English same-document holdout (five factual, two comparisons,
two SQL/table cases, one negative). Source labels and this review were authored
by an assistant and await independent human review. Development and held-out
retrieval metrics use different samples and must not be compared as an improvement.

CV-safe scope: implemented and deployed a private ebook RAG reader with scoped
citations; evaluated a frozen 10-question same-document holdout, with 9/9
answerable HitRate@3 and zero observed request/scope errors. State the small sample
and do not describe this as 100% answer accuracy or a latency SLA.
