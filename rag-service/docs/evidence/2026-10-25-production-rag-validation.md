# Production RAG validation — 2026-10-25

Status: **DEPLOYED; RETRIEVAL GATE PASS; ANSWER GATE FAIL; PUBLIC PDF RENDER BLOCKED**.
Owner approved publishing/merge/deploy conditional on passing CI. Objective is
measured RAG evidence for a CV, not changing a CV PDF. Do not promote local
numbers into production measurements.

## CI candidate

- [PR #37](https://github.com/phandinhphuc1234/SE330_BE/pull/37), candidate
  `b8c2c2a1b3822d89c2d16d7dff4460eca1f156ad`.
- [PR workflow](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40149909829):
  Java verify success, RAG tests success, CodeQL success, SonarCloud success.
- Linux/Python 3.11: **813 tests passed**, 1 warning, coverage **87%**
  (6024 statements, 791 missed). This is CI, not generation-quality evaluation.
- Production image scan/publish/deploy are separate main-branch jobs; PR jobs
  deliberately skip those actions.

## Release security gate and dependency patch

- PR #37 merged as `1ef8feee0e3acead2e3980b23f49e4b10a418358`.
- [Main release run](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40150538991)
  passed Java/RAG tests and the backend image scan, but failed the RAG image scan.
  Production deployment was **skipped**, not bypassed. The VPS retained its
  previous application images.
- Trivy identified fixed HIGH/CRITICAL findings in Pillow 12.3.0 and pypdf
  6.19.0. Update runtime floors to Pillow `>=13.0.0,<14.0` and pypdf
  `>=6.21.2,<7.0`. The regenerated lock changes **only these two package
  versions**; all other locked versions are unchanged.
- Primary release notes: [Pillow 13.0.0](https://pillow.readthedocs.io/en/stable/releasenotes/13.0.0.html),
  [pypdf 6.21.2](https://github.com/py-pdf/pypdf/releases/tag/6.21.2).
- Local Windows/Python 3.12 with the patched dependencies: **813 passed**, 87%
  coverage (6024 statements, 791 missed), 215.99 seconds. This is a code test
  result, not a generation-quality or production-performance result.
- Real parser compatibility check: the unchanged 86,822-byte reference PDF has
  its expected SHA-256, six pages and six chunks. All six ordered content
  checksums still match `production_verification.CHUNK_SHA256`. pypdf reading
  and a Pillow PNG encode/decode roundtrip passed. No provider requests, DB
  writes, relabeling or threshold changes were used in this compatibility check.
- Fresh local image scanning with the updated vulnerability database confirmed
  the PDF/image findings were resolved, but exposed another CRITICAL advisory:
  [GHSA-6q48-6c7w-9gw2](https://github.com/advisories/GHSA-6q48-6c7w-9gw2).
  Certifi 2026.5.20 trusts affected emSign roots. Add runtime floor
  `>=2026.10.19,<2027.0`; the resolver selected **2026.10.23**. The final lock
  differs from PR #37 in exactly **three versions**: certifi, pillow and pypdf.
  No TLS verification is disabled.
- [PR #38](https://github.com/phandinhphuc1234/SE330_BE/pull/38) tracks these
  patches. Full tests and image scanning are repeated after the certifi update.
  A passing PR does not by itself prove a successful image scan or deployment.
- Final patched local suite: **813 passed**, 87% coverage (6024 statements,
  789 missed), 90.19 seconds on Windows/Python 3.12.
- Fresh Trivy 0.74.0 scan at `2026-10-25T02:16:59Z`: **0 fixed HIGH/CRITICAL
  findings** under the unchanged `--ignore-unfixed --severity HIGH,CRITICAL`
  policy. This does not claim zero vulnerabilities at other severities or no
  unfixed findings. Local candidate image ID:
  `sha256:22de8f07e679a8d0af10fb95d52159028a2d77a565c96c24e9a074418ffc92d9`.
  Raw report is preserved locally under
  `rag-service/tmp/production-eval-20261025/trivy-rag-final.json`.
- [Final PR CI](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40153458465)
  passed Java, RAG, CodeQL and Sonar checks. PR #38 merged as
  `7ce3bc4df525669b9a49ba3edd3b425fd16b470c` at `2026-10-25T02:16:56Z`.
- [New main release](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40153860722)
  completed successfully: Java **325/325** (no failures/errors/skips), RAG
  **813 passed**, 1 warning, 87% coverage; backend and RAG image scans both
  passed; immutable images were deployed and public health verification passed.
- Runtime RAG image verified through the pinned-SSH inventory workflow:
  `ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e`.
  The deployment log confirms separate Library/RAG PostgreSQL backups before
  replacement and healthy Spring/RAG API/infrastructure afterwards. Worker is
  running; this does not invent a Docker worker healthcheck.

## Production reference preparation

- Created a new catalog record through the existing admin UI: book ID `3342`,
  title `The Gift of the Magi`, reference identifier `RAG-EVAL-20261025`.
  No existing book was overwritten and no physical copies were fabricated.
- [Pre-upload inventory](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40154626942)
  confirmed production had **zero RAG documents** on the new runtime before
  the reference upload. Local indexed data was not mistaken for production data.
- After deployment, uploaded the unchanged 86,822-byte PDF through the admin
  UI. It created ebook `10`, document `1`, job `1`, status **INDEXED**. Ebook
  policy is FREE, five concurrent loans, fourteen days. No entitlement rows
  were edited directly.
- [Post-upload inventory](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40154778720)
  confirms production environment, prompt `library-ebook-answer-v2`, embedding
  `gemini-embedding-2-3072-v1`, matching source SHA-256 and all six ordered
  chunk checksums/vector IDs/version `v1`. Exact binding passed for all 20
  original cases, without changing questions or labels.
- [Full production evaluation](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40154999135)
  completed against document `1`. Retrieval passed; answer acceptance failed.
  The unmodified raw reports are preserved below, including failed cases.

## Verified rollback targets before replacement

Current VPS container image prefixes from the read-only preflight were matched
against full immutable references in the last successful production run logs:

- Spring: `ghcr.io/phandinhphuc1234/se330-be@sha256:c76360b5bc623da648cb97242bc271bdc396dc011effa26fb9432c3542829a82`
- RAG: `ghcr.io/phandinhphuc1234/se330-rag@sha256:3fdc54210fc934aaf64d45f1b0e2def5f13b397c11ee043d76313fd192fa3a0c`
- Previous deployed commit: `0e974ae317c5383b1183de09025091feb291a3f2`.

The existing deployment script backs up both databases before replacement and
retains the prior application images for rollback. No schema downgrade or
database restore is authorized or implied by application rollback.

## Production test ledger

- [x] Main build, image security scans and publish pass; runtime RAG digest captured.
- [x] Deployment and public health pass on the new merge commit.
- [x] Inventory source/index IDs and checksums without exporting secrets/text.
- [x] Upload/ingest a legal reference PDF normally if the production corpus is absent.
- [x] Exact PDF + six-chunk checksum match before binding the 20-case labels.
- [x] Dense/hybrid retrieval comparison, unchanged hybrid quality thresholds.
- [x] Run full 20-case hybrid answer evaluation and save failures (30-second pacing, no judge).
- [ ] Answer acceptance PASS (17/20 responses; three provider-rate-limit errors and one false abstention).
- [x] Authorized MEMBER loan/session and public semantic-search evidence.
- [ ] Actual production PDF render and visual citation-page navigation.
- [x] Anonymous rejection on four public ebook endpoints (HTTP 401 / UNAUTHORIZED).
- [ ] Authenticated missing-session and cross-book access rejection (not inferred from unit tests).
- [x] Report observed p50/p95 with sample size; no concurrent-load/SLA claim.

Provider failures remain errors. Never combine successful subsets into a full
PASS, lower thresholds or relabel mismatched content to manufacture CV metrics.

## Public authorization checks on the deployed release

Unauthenticated requests, without cookies, JWT or reading-session header:

| Method | Route | Observed status/code |
| --- | --- | --- |
| GET | `/api/ebooks/3342/reader/content` | 401 / UNAUTHORIZED |
| POST | `/api/ebooks/3342/reader/semantic-search` | 401 / UNAUTHORIZED |
| POST | `/api/ebooks/3342/reader/ask` | 401 / UNAUTHORIZED |
| POST | `/api/ebooks/3342/reading-sessions` | 401 / UNAUTHORIZED |

These checks do not establish authenticated cross-book/session isolation by
themselves. No browser credentials, JWTs or reading-session tokens were extracted
into CLI commands, logs or reports.

## Measured production retrieval results

[Raw retrieval JSON](production-rag-20261025/rag-eval-20261025T023539326662Z.json)
and [generated summary](production-rag-20261025/rag-eval-20261025T023539326662Z.md).
Corpus: one unchanged six-page PDF, six v1 chunks. Dataset: 20 curated questions,
17 answerable and three unanswerable; two modes, 40 sequential search requests.
Ranking metrics are averaged over the 17 answerable questions. Latency samples
include all 20 requests per mode. Timings are measured by the evaluation client
against the **internal RAG API on the VPS**, not public-browser/Spring latency.

| Measurement | Dense | Hybrid |
| --- | ---: | ---: |
| HitRate@3 | 1.000000 | 1.000000 |
| Recall@3 | 0.970588 | 0.970588 |
| MRR | 0.892157 | 0.823529 |
| nDCG@3 | 0.908088 | 0.850660 |
| Mean retrieval ms | 661.5 | 583.4 |
| p50 / p95 retrieval ms (N=20 each) | 519.2 / 724.9 | 558.6 / 653.7 |
| Operational errors / scope violations | 0 / 0 | 0 / 0 |

Hybrid gate **PASS** at unchanged minimum HitRate 0.94 and Recall 0.91. Dense
ranked the relevant evidence better by MRR/nDCG on this small dataset; do not
claim hybrid universally improves retrieval or invent a percentage uplift.
Percentiles use nearest rank; these are observations, not concurrent-load
capacity, SLA, semantic answer accuracy or universal production guarantees.

## Answer evaluation: failed baseline retained

[Raw answer JSON](production-rag-20261025/rag-eval-20261025T024511172892Z.json)
and [generated summary](production-rag-20261025/rag-eval-20261025T024511172892Z.md).

- 20 attempted cases, **17 completed responses**: 15 answered and two abstained.
- Three answer errors: `why-called-magi`, `unknown-city`, `unknown-maiden-name`;
  all HTTP 502 with sanitized provider code **LLM_RATE_LIMITED**. This confirms
  a Gemini quota/rate-limit problem, but does not distinguish RPM from daily
  quota without the provider's current quota details.
- One false abstention on the answerable multi-hop case `two-sacrifices`.
  Expected evidence is pages 3 and 6; standalone top-3 retrieval contains pages
  5, 6 and 2, missing page 3. This is a candidate explanation, not proof of the
  exact effective generation context; inspect its evidence trace before tuning.
- Of the three unanswerable questions, one correctly abstained; two hit provider
  errors. Do not claim three-of-three abstention success on production.
- Failure-inclusive metrics: abstention accuracy 0.800; citation precision
  0.843137; citation recall 0.882353; lexical answer-point coverage 0.705882.
  None is a semantic faithfulness score. No judge was used.
- Observed successful Ask latency: p50 2580.3 ms, p95 3682.1 ms, **N=17 completed
  requests only**. This omits three failed requests and must not be presented
  as the full-run/public-browser p95 or a success-rate/SLA claim.
- Gate **FAIL**: operational errors and incomplete answers. A complete rerun
  remains necessary after quota is available and the false-abstention case is
  addressed. Do not combine a later subset with this run into a fictitious PASS.
- The image does not contain Git, so report `code.commit=None` is expected;
  runtime provenance is tied to the immutable OCI digest and successful release
  run above, not an invented in-container Git revision.

## Public reader test and concrete remaining blocker

- Owner signed in normally as MEMBER. Borrowed ebook 10 through the existing
  FREE-access UI; it displayed an active loan and the Read ebook link. No
  payment, manual loan-row change or role bypass was used.
- Secure reader created/renewed its session. Find passages completed through
  the public Spring API: six scoped passages, including the correct page-3
  evidence that Della's hair reached below her knee. Clicking its citation
  updates the requested page to 3, but the PDF remains unrendered (`3 / -`).
  This is **not** a successful visual citation/PDF acceptance.
- [Read-only VPS diagnostic](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40156410846)
  confirmed the deployed commit and sanitized browser-facing storage origin:
  **`http://127.0.0.1:8333`**. On a remote browser this refers to the reader's
  computer, not the VPS. Spring's presigner uses this public endpoint directly.
  The production Docker network correctly keeps SeaweedFS private; do not
  publish port 8333 or make the bucket public just to work around this mistake.
- Frontend PDF worker asset returned HTTP 200. No console errors were captured;
  absence of console errors does not prove successful PDF rendering.
- Proposed follow-up (requires owner direction): stream PDF through the
  authenticated Spring API after the existing JWT/loan/reading-session checks,
  retaining private SeaweedFS, or deliberately configure a secure HTTPS signed
  storage gateway. Neither has been implemented or exposed in this test.

## CV wording supported now

> Deployed a Spring Boot/FastAPI ebook RAG pipeline on Azure with Gemini
> embeddings, Qdrant and hybrid retrieval; evaluated 20 curated questions on a
> six-page reference corpus, achieving 100% HitRate@3 and 97.1% Recall@3, with
> 654 ms observed internal-retrieval p95 (20 sequential hybrid requests).

Keep the small-corpus qualifier. Do not add "100% answer accuracy", "20/20
grounded answers", "production-ready PDF reader", large-scale performance or
load capacity until the remaining gates have actually passed.
