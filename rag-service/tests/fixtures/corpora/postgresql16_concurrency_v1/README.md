# PostgreSQL 16 Concurrency Control - technical RAG corpus v1

Snapshot: 2026-10-25. This corpus is **separate** from Gift of the Magi.
Do not replace the Magi golden dataset, checksum constants, data or reports.

## Sources and provenance

Official chapter: https://www.postgresql.org/docs/16/mvcc.html
Includes sections 13.1-13.7 and Tables 13.1-13.3, not the entire PostgreSQL manual.
The `source/` HTML snapshot and its full legal notice are retained. See LICENSE.txt.
The PDF is a re-typeset derivative, not an official vendor PDF. Source URLs and
the complete legal notice are inside it. Wording/code/cells are retained;
navigation/duplicate section menus are omitted, punctuation is normalized, and
merged table header cells are flattened into explicit column headers.

Repository-root output: `output/pdf/postgresql-16-concurrency-control-v1.pdf`.
The generated PDF has 22 pages / 151,620 bytes. Its SHA-256 is:
`73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26`.
Printed page numbers equal one-based PDF page numbers. `source-manifest.json`
pins nine HTML checksums and maps every retained source block to PDF pages.
There are 190 retained blocks: 22 headings, 135 prose paragraphs, 19 definitions,
3 tables and 11 preformatted examples/output blocks.

## What the current evidence proves

`ingestion-v1.json` records an **offline parse/clean/chunk run through real app
code**, not an upload/index/retrieval/answer run. It contains 35 chunk texts with
SHA-256 and page provenance; no DB IDs or secrets. `OFFLINE-PG16` is used only
inside the in-memory pipeline, never a positive runtime identifier.

- PDF structure/text-layer gates passed; 22/22 pages produced text.
- Chunking v1, `chunk_size=512`, `chunk_overlap=64` passed its quality gate:
  zero errors, warnings and exact duplicate chunks.
- Chunk lengths: 460-2,650 characters, mean 1,745.00.
- ApproxTokenCounter: 137-663 tokens, mean 440.37. This approximate counter is
  **not** the tokenizer used for LlamaIndex's 512-token splitting budget;
  do not claim every chunk is <=512 according to every tokenizer.
- All source-excerpt anchors for 36 answerable questions survived extraction.
- All 22 rendered pages were reviewed; table and SQL pages were also inspected
  at higher resolution. A second build produced the same PDF checksum.
- Focused corpus/binder suite: 25 passed; full RAG suite: 838 passed (45.59s).

These are fixture/pre-index checks. They prove neither semantic answer accuracy,
provider capacity, Qdrant contents nor production PDF rendering.

## Frozen question split

`questions.json` was authored from the official source and frozen **before any
retrieval run or tuning**. No retrieved rankings were used to choose labels.

| Question type | Dev | Held-out | Total |
| --- | ---: | ---: | ---: |
| Direct/factual | 15 | 5 | 20 |
| Multi-section / comparison | 8 | 2 | 10 |
| SQL / table interpretation | 4 | 2 | 6 |
| Out-of-corpus / insufficient information | 3 | 1 | 4 |
| Total | 30 | 10 | 40 |

This is same-document question holdout, not unseen-document generalization.
Language is English in v1; Vietnamese smoke questions must be reported separately.
Labels are source-checked by the authoring assistant, **not independently
human-reviewed**. The held-out questions may be read to audit labels, but must
not be run/tuned against during development. Freeze a candidate before one
held-out pass and retain failures as well as successes.

Each answerable item has an expected answer, keyword answer points, exact source
excerpts and PDF/chunk references. Keyword coverage from the existing runner is
a lexical diagnostic, not an LLM judge or semantic correctness verdict. Review
answer meaning and citation entailment separately.

Six excerpts occur in two overlapping chunks. Both are retained as alternatives,
never selected after seeing retrieval results. A sidecar stores required evidence
groups; `evidence_group_coverage` counts one alternative as sufficient for that
excerpt. The existing runner is unchanged: its chunk Recall@k / citation recall
count the union of relevant chunks, including overlap. Some union labels have
four chunks, so perfect chunk Recall@3 is not possible for every question.
Do not rename group coverage as chunk recall or compare unlike metrics.

## Reproduce locally

From the repository root, use an authoring Python with `reportlab` and `lxml`:

```powershell
python rag-service/scripts/corpora/build_postgresql16_pdf.py --fonts C:/Windows/Fonts
```

Arial/Consolas are authoring-only system fonts, not shipped to production. The
builder does not download or silently update upstream content. A source/PDF
change requires a new corpus revision and label/page review, not editing old
hashes until a binder accepts the file.

Use the existing RAG virtual environment for offline ingestion (no provider call):

```powershell
rag-service/.venv/Scripts/python.exe rag-service/scripts/corpora/inspect_postgresql16.py
```

`source-manifest.json` and `ingestion-v1.json` are generated artifacts. Regenerate
them using their scripts, not manual edits to checksum fields. The saved run is
Windows/Python 3.12/PyMuPDF4LLM 1.28.0. The exact ordered chunk hashes must also
match the deployed parser runtime; reject a mismatch, investigate it and create
a separately reviewed revision if necessary.

## Production binding and evaluation protocol

1. Add a **new** Library title, e.g. `PostgreSQL 16 - Concurrency Control`,
   author `PostgreSQL Global Development Group`, identifier `PG16-CONCURRENCY-V1`.
   Upload the exact generated PDF as a new free-access ebook. Do not overwrite
   book 3342 / ebook 10 / document 1 (the Magi regression fixture).
2. Wait for the real job's terminal `INDEXED` state. Capture the source checksum,
   actual Library IDs, every ordered chunk SHA/version and nonempty vector ID
   using the existing read-only production inventory mode. Never print tokens.
3. Bind **dev only** from that inventory, from the `rag-service` directory:

```powershell
python -m app.evaluation.corpus_binding --reference tests/fixtures/corpora/postgresql16_concurrency_v1/ingestion-v1.json --inventory path/to/inventory.json --document-id ACTUAL_ID --split dev --output path/to/fresh-dev.json
```

`ACTUAL_ID` is deliberately not filled with a made-up positive ID. The command
refuses source/hash/count/version/index/scope mismatches and existing output files.
It writes an ordinary EvaluationDataset plus an evidence-group sidecar, with
status `bound-not-evaluated`.

4. Run dense vs hybrid dev retrieval first, no generation/judge. Use internal
   credentials only in the environment; do not pass them as command arguments:

```powershell
python -m app.evaluation.run_eval --dataset path/to/fresh-dev.json --modes dense hybrid --top-k 3 --skip-answers --output-dir path/to/new-report-directory
```

5. Review misses (especially table cells, comparisons and cross-page SQL). Keep
   baseline and failed reports. If tuning is justified, tune dev only. Running
   header/footer text survives in some current chunks; record this limitation,
   do not infer semantic quality from the structural PASS.
6. Once Gemini quota is confirmed, run 30 dev answer cases as **one full run**,
   pace requests, keep 429/timeouts as errors rather than counting them as correct
   abstentions. Do not stitch successful subsets into a passing full result.
7. Freeze retrieval/prompt/candidate settings, bind `--split heldout` separately,
   then run its 10 cases. Do not copy the old Magi gate thresholds/metrics as
   achieved results for this corpus. A 1/1 held-out negative-case result would
   be a very small sample, not general abstention accuracy.
8. Test MEMBER borrow -> reader -> answer -> citation with this exact production
   title. Existing production private-PDF origin/reader blocker is independent
   of this corpus and remains unresolved by these files. Do not expose SeaweedFS
   or turn off authorization to make a demo pass.

## Pending release gates

- [x] Licensed source snapshot + reproducible selectable-text PDF.
- [x] Source-based 40 questions and frozen 30/10 split.
- [x] Offline structure/text/clean/chunk checks and reviewed table/SQL rendering.
- [x] Fail-closed runtime binder and regression tests (test-only IDs stay in tests).
- [ ] Independent human label review.
- [x] New production title/PDF upload and actual INDEXED/checksum/chunk-vector-ID verification:
  book 3343 / ebook 11 / document 2 / job 2; 35 ordered hashes matched local.
  This is not a separate Qdrant point-query test. See the production checkpoint below.
- [ ] Dev dense/hybrid retrieval comparison on this corpus.
- [ ] Full dev and frozen held-out answer runs with real provider errors retained.
- [ ] Production reader/citation positive and authenticated negative smoke.
- [ ] Evidence-backed CV metric on this technical corpus; no invented gains.

## Production checkpoint

Read-only inventory run `40180403668` succeeded on 2026-10-25. The exact source
and all ordered v1 chunk hashes matched. The dev-only binding is in
`docs/evidence/pg16-production-20261025/bound-dev.json` with an alternative-group
sidecar. Its status is **bound-not-evaluated**; no held-out binding/run was made.
Magi remains unchanged. Full details:
[production upload and binding](../../../../docs/evidence/2026-10-25-postgresql16-corpus-preparation.md).

## PR-only production retrieval workflow

The owner approved commit/push, a separate PR, CI and the 30-case dev retrieval
benchmark. Merge/deployment and LLM answer evaluation are **not** approved by
that checkpoint. Use the existing manually dispatchable workflow with a new,
separate job/mode (new workflow files cannot be manually dispatched before
GitHub has registered them on the default branch):

```powershell
gh workflow run rag-production-check.yml --ref codex/rag-pg16-retrieval-benchmark -f mode=pg16-retrieval-dev -f document_id=2
```

The job first requires successful PR CI for its **exact** helper commit. It then
uses the existing pinned SSH identity/host key and a temporary bundle inside the
already-deployed immutable RAG container. The helper is not copied over `/app`,
does not build/deploy/restart services and opens no ports. Before search, it
checks the runtime image digest, live source/ordered chunk hashes and all 35
Qdrant point payloads for matching content/scope/version/page provenance.

Only 30 dev questions are sent, once for dense and once for hybrid, `topK=3`.
The helper has no answer/judge flag; generation is hard-disabled. It preserves
raw failures, scope violations, the original chunk metrics, a separately named
evidence-group coverage metric and observed latency samples. An operation PASS
does not imply semantic release quality: the latter stays unset for baseline
collection. Dense runs first; caches can affect latency, so this is neither a
controlled cold/warm experiment nor a load test/SLA. Searches may populate normal
caches/counters and consume embedding quota. No held-out query or entitlement,
source, database-content or vector-index mutation is requested.

Artifacts contain only reference/provenance/results, never keys or signed URLs.
Temporary evidence directories are retained for diagnosis, not recursively deleted.
