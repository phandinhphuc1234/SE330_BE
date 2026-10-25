# Production RAG validation — 2026-10-25

Status: **PR #37 MERGED; RELEASE BLOCKED BY IMAGE SECURITY SCAN; PATCH VALIDATION IN PROGRESS**.
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
- Fresh local image scan, patch PR CI and a new main release remain required.

## Production reference preparation

- Created a new catalog record through the existing admin UI: book ID `3342`,
  title `The Gift of the Magi`, reference identifier `RAG-EVAL-20261025`.
  No existing book was overwritten and no physical copies were fabricated.
- PDF upload/ingestion will occur after the patched runtime is deployed, so
  indexing evidence belongs to the candidate actually being evaluated.

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

- [ ] Main build, image security scans and publish pass; capture new digests.
- [ ] Deployment and public health pass on the new merge commit.
- [ ] Inventory source/index IDs and checksums without exporting secrets/text.
- [ ] Upload/ingest a legal reference PDF normally if the production corpus is absent.
- [ ] Exact PDF + six-chunk checksum match before binding the 20-case labels.
- [ ] Dense/hybrid retrieval comparison, unchanged hybrid quality thresholds.
- [ ] Full hybrid answer/citation/abstention evaluation (30-second pacing, no judge).
- [ ] Authorized public reader, actual PDF render, citation navigation.
- [ ] Anonymous, missing-session and cross-book access rejection.
- [ ] Report observed p50/p95 with sample size; no concurrent-load/SLA claim.

Provider failures remain errors. Never combine successful subsets into a full
PASS, lower thresholds or relabel mismatched content to manufacture CV metrics.
