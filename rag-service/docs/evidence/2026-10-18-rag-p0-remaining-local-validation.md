# RAG v1 — remaining local validation, 2026-10-18

> Historical snapshot, superseded for current status by
> [2026-10-24 local release gates](2026-10-24-rag-local-release-gates.md).
> Full v2 20/20 PASS completed 19/10 and was verified after timeout 24/10;
> adversarial PDF and browser Ask/citation/abstention checks also passed 24/10.
> No old partial report or test result below has been overwritten.

Branch: `codex/rag-v1-production-readiness`; base commit `0e974ae` plus working changes.
Scope: local only. No production changes, merge, or deployment were performed.

## 1. Gemini availability is not a full quality pass

At 08:16 UTC, one small request using the running RAG container's configured
`gemini-2.5-flash` and credential returned HTTP 200 and the expected JSON.
It consumed 12 prompt tokens and 5 output tokens in 2.11 seconds, with no retries.
This demonstrated availability for that request, not sufficient remaining daily quota.

## 2. Full prompt-v2 evaluation — 19/20, provider-blocked

Report artifacts, preserved without editing:

- `app/evaluation/reports/rag-eval-20261018T083851792023Z.json`
- `app/evaluation/reports/rag-eval-20261018T083851792023Z.md`

The existing runner attempted all 20 cases of dataset `1.0.0`, hybrid retrieval,
top K 3, a 30-second answer interval, no LLM judge and unchanged thresholds.
The server was confirmed to run `library-ebook-answer-v2` before evaluation.

| Result | Value |
|---|---:|
| Completed answers | 19 / 20 |
| Answerable cases answered, not abstained | 17 / 17 |
| Unanswerable cases abstained | 2 / 3; third call failed |
| Operational errors | 1: `unknown-maiden-name`, HTTP 502 / `LLM_RATE_LIMITED` |
| Scope / citation metadata violations | 0 / 0 |
| Hybrid HitRate@3 / Recall@3 | 0.941 / 0.912 |
| MRR / nDCG@3 | 0.725 / 0.762 |
| Mean retrieval latency in this local run | 1471.5 ms |
| Citation precision / recall | 0.882 / 0.912 |
| Lexical answer-point coverage | 0.765 |
| Abstention accuracy, failed case kept in denominator | 0.950 |

`unknown-city`, which failed the v1 response contract, now abstained correctly
in this v2 run. This is useful case-level evidence, not a full PASS.
The runner exited **1** with `hybrid:errorCount` and `hybrid:incompleteAnswers`.
No repeated provider retries, model switch, threshold change or golden-label
change was used to hide the last failure. A fresh full run remains required;
combining successful subsets does not make this report pass.

The `two-sacrifices` response cited source chunks on pages 5, 6 and 2;
`unusable-gifts` cited pages 6, 5 and 2. The original six-page PDF was read
locally: page 5 contains the hair discussion and page 6 the sold-watch statement.
These are multi-page API/source checks, **not browser citation-navigation tests**.
Extra citations and incomplete golden labels limit the interpretation of citation
precision; no blanket semantic-faithfulness claim is made.

## 3. Real failure smoke found and fixed a persistence bug

A separate zero-page PDF was uploaded to a new, unused private S3 key and
enqueued through the real internal ingestion API. No existing ebook was replaced.

Before the fix, local job **5**, document **3**, ebook **1576208978** exhausted
four attempts but remained `PROCESSING` while Celery reported `FAILURE`.
The underlying PDF error was `PDF has no pages.`; failure persistence itself
raised SQLAlchemy `MissingGreenlet`.

Cause: `session.rollback()` expires ORM objects, including their identifiers.
The exception handler accessed `job.id` after rollback, triggering implicit async
IO outside SQLAlchemy's awaited context. `expire_on_commit=False` does not prevent
rollback expiration.

Fix in `app/ingestion/pipeline.py`: reload using the scalar `ingestion_job_id`
argument, not an attribute of the expired job. Two new regression cases exercise
an expired job and a job that disappears before failure persistence; both retain
the original exception, and the existing row is saved as terminal `FAILED`.

The rebuilt worker reran the same invalid source as job **7**:

```text
QUEUED → PROCESSING → RETRYING → ... → FAILED
attempts=4 (initial attempt + max_retries=3)
completed_at present
SQL chunks=0; active Qdrant points=0
Celery state=FAILURE
polling API errorCode=INGESTION_FAILED
```

This is a **passing negative smoke**: invalid PDF content fails clearly and
does not leave a false active job. Permanent validation errors still consume
the current bounded retry budget; classifying them as non-retryable is a future
optimization, not something claimed implemented here.

Historical job 5 was then manually reconciled to `FAILED` **only after** checking
its exact readiness filename, document/ebook IDs, exhausted attempts and Celery
`MissingGreenlet` result. Its metadata records the manual action and links job 7.
It is not counted as an automatic post-fix success. No rows or source files were deleted.

## 4. Temporary source failure and recovery

Two independent local fixtures exercised actual API, Redis/Celery, PostgreSQL,
SeaweedFS, Gemini embeddings and Qdrant rather than a mocked worker:

| Run | Job / document / ebook | Result |
|---|---|---|
| Initial diagnostic | 6 / 4 / 1026229968 | Missing object → RETRYING → object restored → INDEXED, attempts 2 |
| Rebuilt worker, after fix | 8 / 5 / 1836146605 | Missing object → RETRYING → object restored → INDEXED, attempts 2 |

The source object was deliberately absent at enqueue time and uploaded only
after observing `RETRYING`; existing objects were never deleted to inject failure.
`completed_at` was absent during retry and present at completion. Both successful
fixtures have **6 SQL chunks and 6 active scoped vectors**. This proves recovery
from a temporary source-storage failure, not recovery from every provider outage.

## 5. Public Ask negative checks, without generation quota

`scripts/smoke_public_reader.py` now checks Ask as well as semantic search:

| Check | Actual local result |
|---|---|
| Anonymous semantic search | 401 |
| Anonymous Ask | 401 |
| Authenticated Ask without reading session | 400 / `READING_SESSION_REQUIRED` |
| Ask a different book with the permitted book's session | 403 / `READING_SESSION_FORBIDDEN` |
| Authorized semantic search | 3 correctly scoped hits |
| Protected source PDF | 6 pages, 86,822 bytes |
| Close only the newly created session | Successful HTTP response checked |

Command shape: `python scripts/smoke_public_reader.py --book-id 3342 --denied-book-id 3226 --skip-answers`.
Local seeded demo credentials were loaded into the process only; no credentials,
JWTs, session tokens or signed URLs are included in reports.
The run's trace was `rag-smoke-3891b9b8-aa00-4669-8075-740e98e3be60`.
After API/worker alignment to the rebuilt image, the same checks passed again
under trace `rag-smoke-e3f39fb1-f0f9-464d-9ea8-228d5212b4b7`. Backend health was
UP and all four Prometheus targets were up (Spring, RAG API, Qdrant, Prometheus).
The last two successful generation checks were deliberately disabled after the
evaluation hit quota. No public prompt-v2 generation PASS is claimed.

Nine new script regression cases cover the success path, fail-closed checks,
invalid PDF page metadata, cleanup failures, and argument validation.

## 6. Final local suite and image gate

```text
python -m pytest --cov=app --cov-report=term-missing --cov-report=xml -q
803 passed in 137.67s
TOTAL: 5924 statements, 726 missed, 88% coverage
```

No failure or skip was reported. This full run used the existing Windows Python
3.12.6 virtualenv; the real API/worker image uses Python 3.11. Linux/Python-3.11
CI is still a separate gate. The previous 320-test backend PASS remains the
backend evidence; no Java source was changed in this follow-up and Maven was
not rerun here.

Local rebuilt image digest:

```text
sha256:40d8acda91b2af32854af798fe1e5777f6d426cec90071196b5c2b2ed463f60e
```

Trivy 0.74.0 exited 0 using `--scanners vuln --severity HIGH,CRITICAL
--ignore-unfixed --exit-code 1`. The report's `Metadata.ImageID` matches the
rebuilt digest. Reported fixed HIGH/CRITICAL findings: **0**.
Both local API and worker containers were verified to use this rebuilt digest;
RAG health and metrics returned HTTP 200 after API recreation.
Artifact: `docs/evidence/2026-10-18-trivy-rag-candidate-post-smoke.json`.
The scan emitted vendor-severity/SBOM interpretation warnings; this is a pass
under the configured policy, not proof that no possible vulnerabilities exist.

## 7. Still not complete

- A full 20/20 prompt-v2 answer gate is still blocked by provider quota.
- A real adversarial-PDF/Gemini smoke and public/browser generation/citation
  checks have not been completed. Structural injection regressions passed.
- Commit/push/PR/CI and all production release/rollback checks are not covered
  by these local results. No production release is authorized by a partial gate.
- The user explicitly chose to keep the changes local without commit/push;
  no PR was created and no CI/release workflow was dispatched.

Original golden document 1 and public upload document 2 each still have 6 SQL
chunks / 6 active vectors. Readiness fixtures are explicitly identifiable and
preserved; no golden labels, retrieval weights, embedding version, source PDFs
or existing user accounts were changed.
