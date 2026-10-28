# Technical RAG corpus preparation - 2026-10-25

Status: **PRODUCTION DEV RETRIEVAL BASELINE COMPLETE; ANSWER / HELD-OUT / READER GATES PENDING**.

User approved replacing the short story as the main demo with the PostgreSQL 16
Concurrency Control chapter, while retaining the story as regression data.

## Production checkpoint after ADMIN login

- New public title: [PostgreSQL 16 - Concurrency Control](https://library.flashsale123.tech/books/3343).
  Book **3343**, ebook **11**, RAG document **2**, ingestion job **2**.
- Added corporate author `PostgreSQL Global Development Group` (author 5210),
  with official source attribution. Catalog identifier `PG16-CONCURRENCY-V1`,
  category Databases; no invented publication date or physical copies.
- Uploaded the exact PDF through the authorized ADMIN UI. Protected storage is
  still SeaweedFS RAW/PRIVATE. Policy: free ebook, 5 concurrent loans, 14 days.
- UI reached `Indexed`. Read-only VPS inventory workflow
  [40180403668](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40180403668)
  then succeeded, generated at **2026-10-25T09:23:07.644625Z**.
- Actual source SHA-256 and **all 35 ordered chunk hashes** match the reviewed
  offline v1 reference exactly. Every chunk has a nonempty vector identifier;
  inventory is DB/job evidence, not a separate Qdrant point-query or retrieval test.
- Runtime inventory reports prompt `library-ebook-answer-v2`, embedding version
  `gemini-embedding-2-3072-v1`. No model/key/config change was made.
- Fail-closed binder produced **30 dev cases**, scope `bookId=3343`, `ebookId=11`,
  `documentId=2`, status `bound-not-evaluated`. Held-out was not bound or run.
- The old Magi PDF and six ordered chunk hashes still match their original
  verifier constants. No old source/chunks were overwritten.

Raw inventory and binding: [production evidence folder](pg16-production-20261025/).
Screenshot: [indexed status](2026-10-25-pg16-production-indexed.jpg).

At the upload checkpoint, no PostgreSQL retrieval/answer metrics had been
measured. The existing published evaluation workflow is intentionally specific to Magi, so it cannot
be pointed at document 2 to manufacture a result. A separate corpus benchmark
workflow/PR was approved by the user for commit/push, CI and a dev-only retrieval
run. A dedicated `pg16-retrieval-dev` job/mode extends the existing dispatchable
workflow so no merge is needed just to register a new workflow file. The helper
checks exact-revision CI, runtime digest, source/chunks and Qdrant payloads;
it never calls answers/judge or runs held-out. This does not require deploying a
different backend or exposing internal service ports. The completed retrieval
result is recorded separately below; answer metrics remain pending.

## Production dev retrieval result (25/10; documented 28/10)

- Separate [PR #40](https://github.com/phandinhphuc1234/SE330_BE/pull/40), helper
  commit `139d1b50613e9857d3e2a898d46de0ac56387dca`.
- [Exact-commit CI 40188491366](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40188491366)
  passed: 325 Java / 860 RAG tests, CodeQL and SonarCloud; app coverage 87%.
  Image publication and deployment jobs were skipped.
- [Benchmark 40188794204](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40188794204)
  passed all operation checks. Qdrant preflight verified 35/35 points; 30 dev
  queries ran once per mode, topK=3, without answer/judge or held-out calls.
- Over 27 answerable dev questions, dense / hybrid chunk Recall@3 was
  **93.21% / 98.77%**, HitRate@3 **26/27 / 27/27**, MRR **0.944444 / 1.0**.
  Hybrid observed p95 was **675.6 ms**, N=30 sequential internal-VPS requests,
  with zero request errors or cross-scope violations in either mode.
- Hybrid retained a 2/3 evidence miss on `pg-m07`; labels were not changed.
  These are development retrieval measurements, not answer accuracy, an SLA,
  held-out acceptance or authorization to merge/deploy.

Full denominators, limitations, CV wording and unchanged raw reports:
[production retrieval baseline](2026-10-25-pg16-production-retrieval-baseline.md).

## Completed local preparation (earlier checkpoint)

- Nine official HTML pages downloaded once, source/license retained and checksummed.
- Re-typeset PDF: 22 selectable-text pages, 151,620 bytes, three tables and eleven
  preformatted examples/output blocks. All pages rendered/reviewed, with extra
  table/SQL inspection. Two builds gave identical SHA-256:
  `73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26`.
- Forty English source-checked question specs: 20 factual, 10 comparison/multi-section,
  6 SQL/table, 4 unanswerable. Frozen 30 development / 10 held-out before retrieval.
- Real app offline parse/clean/chunk: 22 text pages -> 35 v1 chunks (512/64),
  structural quality PASS, 0 errors, 0 warnings, 0 exact duplicate chunks.
- Character lengths min/mean/max: 460 / 1,745 / 2,650. Approximate token lengths
  137 / 440.37 / 663; not the same tokenizer as the splitter budget.
- All labelled source anchors survived extraction. Six overlapping excerpts have
  alternative relevant chunks; original runner metrics are not silently changed.
- `corpus_binding.py` checks source + every ordered chunk hash/version + actual
  runtime scope/vector IDs before generating a runnable dev or held-out dataset.
  It cannot upload data or report an evaluation PASS.
- New focused regression suite: **25 tests passed**. Full RAG suite:
  **838 passed in 45.59 seconds**, no failed/skipped tests in that run. This is
  local automated-test evidence, not a CI or production-evaluation result.

## Benchmark candidate validation (new PR)

- Focused corpus/protocol suite: **47 passed** (25 binder/corpus + 22 workflow,
  point-preflight and no-generation protocol tests), no provider/service calls.
- Full candidate RAG suite: **860 passed in 132.07s**, app coverage **87%**
  on Windows/Python 3.12.6. This supplements, not replaces, the earlier 838-test
  preparation checkpoint. PR CI/Python 3.11 and the real retrieval benchmark
  subsequently passed as recorded above.
- Workflow YAML parsed and every run block passed `bash -n`.
- Byte-pinned HTML/JSON attributes prevent Windows/Linux checkout line-ending
  conversion from silently changing source/question checksum semantics.
- The PR starts from deployed `main` (`7ce3bc4`) rather than pulling in the
  two unmerged diagnostics commits from PR 39. Original local files/reports are
  preserved in their checkout; only explicit corpus/benchmark assets are included.

## Boundaries and pending checks

The earlier offline preparation called no providers and wrote no service data.
The subsequent production upload/index is a real DB/object-storage/indexing
mutation, explicitly separate from that offline result. It establishes neither
retrieval accuracy nor answer accuracy, throughput or latency. The old Magi
production numbers cannot be transferred to PostgreSQL. No LLM generation was
started during this production-upload checkpoint.

The user logged in as ADMIN and the role was verified in the visible profile.
An older tab failed to persist the initial book submission, and after reload
showed role unknown. The separate tab that had successfully created the author
retained a working ADMIN session and created book 3343. Catalog readback was
checked before retry, so no duplicate title was created. The refresh/session
cause is not diagnosed here. No credentials, role changes, bucket exposure or
PDF-proxy redesign were attempted.

The pre-existing production PDF-origin blocker remains separate and unresolved;
new corpus preparation does not fix reader rendering. Publication of a separate
benchmark PR is published and the dev retrieval run is complete; merge/deploy
and generation remain out of scope for that approval.

Reproducible details, limitations and next run commands:
[corpus runbook](../../tests/fixtures/corpora/postgresql16_concurrency_v1/README.md).

Official source: [PostgreSQL 16, Chapter 13](https://www.postgresql.org/docs/16/mvcc.html).
