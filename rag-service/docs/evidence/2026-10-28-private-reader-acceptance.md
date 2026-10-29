# Production private reader and PostgreSQL acceptance - 2026-10-28

Status (updated 2026-10-29): DEPLOYED; PDF/citation/browser abstention and the
10-case held-out contract passed. Full dev answers, authenticated HTTP negatives
and independent human semantic review remain open. Historical blockers below
are retained; the disk-capacity blocker was resolved by approved maintenance.

## Approval and pre-deployment checkpoint

Owner approved the PDF fix, answer/citation/abstention and held-out evaluation,
separate frontend/backend PRs, merge/deploy after CI, and frontend dependency patches.
No new public ports, public bucket or token extraction is authorized/needed.

- Backend PR #41 helper/source `5f41069baa9e964e9cb7587ca7c8892dcca1a15c`.
  CI 40653200679 passed 360 Java tests / 815 RAG tests, CodeQL/Sonar; RAG coverage 87%.
- Frontend PR #29 final helper `409c112933f84eac93b28ecad8073303e0a5a526`.
  CI 40653957944 passed tests/lint/build/audit, Sonar and Vercel preview.
  Merged as `4e401adb42f6eced2f96f7fbff5eadde1bc72575`.
  Production deployment 7380363989 succeeded:
  https://se-330-f2zm1iu4g-phuc-a7ba.vercel.app
- Patched locked frontend versions: Next.js 16.4.3, PDF.js 6.4.299, Zod 4.6.7;
  compatible transitive updates. Full npm audit reported zero vulnerabilities.
  Versioned PDF worker matches the installed dependency; old worker is retained.
- Read-only VPS preflight 40653769298 attempt 1 timed out connecting over SSH;
  attempt 2 succeeded. Spring health UP; RAG API/worker and dependent containers
  were running. No network/firewall/config change was made to recover access.

## Rollback targets captured before backend merge/deploy

Preflight reports deployed source `7ce3bc4df525669b9a49ba3edd3b425fd16b470c`.
Live image-ID prefixes matched the successful deployment run 40153860722:

- Backend: `ghcr.io/phandinhphuc1234/se330-be@sha256:c2a4630a553224b87402b4d5f2ac32c615f5dab503c6e7c19af683c958bd1562`.
- RAG: `ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e`.
- Previous Vercel production: deployment 6935132950, source `d74284f1c315b92243cbd3cdda28cc479327bdef`,
  https://se-330-9xmo5uje1-phuc-a7ba.vercel.app

## Gates still pending

- [x] Backend reader PR #41 and dependency follow-up PR #42 merged.
- [x] Main Java/RAG tests and both immutable-image security gates passed.
- [x] Healthy backend/RAG deployment after capacity recovery, release attempt 2.
- [x] PostgreSQL PDF loads through private reader API (22 pages); S3 bytes unchanged.
- [ ] File/range/HEAD and missing/foreign/closed-session checks in production.
- [x] Browser citation opens the expected PDF page and unsupported question abstains.
- [x] Frozen candidate and first complete 10-case held-out evaluation; raw responses retained.
- [ ] Separate full 30-case dev answer run and independent human semantic review.

No new PostgreSQL answer or held-out result has been measured at this checkpoint.
This sentence describes the original checkpoint, superseded by the 29/10 result below.

## 29/10 completed maintenance, deployment and held-out checkpoint

- Owner-approved maintenance [40804673423](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40804673423)
  verified a private 114-file logical backup and preserved all logical file bytes,
  ownership/mode/mtime. Exactly 17 same-length data files released
  **18,252,746,752 bytes** of beyond-EOF allocation. No file/volume was deleted.
  Backup archive SHA-256: `099b1e7cd45e26e084323e6b7edf280cfb66d07e680b6c80def65a2de6bb56b9`.
  All **18 S3 objects / 919,756 bytes** matched before/after, including the source PDF.
- Maintenance helper `5cff07f00177347ad381cb009171c4895739d8cd` passed
  CI 40804173170: 360 Java / 830 Python/RAG tests, CodeQL and SonarCloud.
  First maintenance attempt 40803910885 failed on verifier file permissions
  before storage stop/data mutation; worker automatically resumed. Stdin transport
  fixed this without loosening permissions; no intermediate failure is hidden.
- Deployment [40698306283 attempt 2](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40698306283)
  succeeded with the already-scanned candidate digests and commit
  `d94f24e3fec8cb3d624663cd12b23a4ce9cf8f9a`; Flyway installed rank 48, public health UP.
  Post-release read-only 40806301485 confirmed the commit/new image prefixes,
  healthy RAG/backend and **20G free / 31% used**. The 8 GiB gate was not reduced.
- MEMBER browser: PDF rendered with 22 pages; factual development smoke returned
  Read Committed with page-5 citation; clicking it changed the reader to 5/22.
  Redis/TTL development negative abstained. Anonymous PDF GET/HEAD/Range each 401.
  Authenticated Range/HEAD and missing/foreign/closed-session production probes
  remain unrun (not inferred from the CI tests).
- Provider-free new-image plan 40805217254 verified all 35 Qdrant points and froze
  candidate `2ac6cc93f707122e8370ddb09fe82490ca4cc2c16a4fa318fd37b3c4d858785f`.
- Held-out [40806368432](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40806368432)
  completed all 10 questions once with hybrid/topK=3, 30-second pacing, no judge
  or tuning: zero errors/scope/page-metadata violations, HitRate@3 **9/9** over
  answerable cases, Recall@3 **91.67%**. Negative abstention **1/1**; only one
  negative sample. Contract PASS is not 100% semantic answer accuracy.

Full raw provenance, source review (including incomplete pg-m08 explanation),
latency sample limits and remaining gates are in the
[held-out answer baseline](2026-10-29-pg16-heldout-answer-baseline.md).

## Verified continuation: release candidate and capacity blocker

- [PR #42](https://github.com/phandinhphuc1234/SE330_BE/pull/42), source
  `9fa7d9ab0c2dbe1f0b3ea0f9c7a89667c9f71fa8`, passed PR CI
  [40659333735](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40659333735)
  and was merged as `d94f24e3fec8cb3d624663cd12b23a4ce9cf8f9a`.
  NLTK is locked to 3.10.4; the earlier HIGH finding was not suppressed.
- Main release [40698306283](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40698306283)
  passed **360 Java tests** (zero failures/errors/skips), **815 RAG tests**
  (one warning), and both HIGH/CRITICAL image gates. These are CI test counts,
  not production RAG answer accuracy.
- The scanned, published candidate images are:
  - Backend: `ghcr.io/phandinhphuc1234/se330-be@sha256:53fdf974f8a2cfb750aeddd193e5eb349eaacb3ccacb7149c12ccca9a497b40a`.
  - RAG: `ghcr.io/phandinhphuc1234/se330-rag@sha256:3f978ac7617e80895abb62635fe598af51ce24172560c33e6d3955bb37d62197`.
- Deployment failed at the existing preflight requirement of **8 GiB free**,
  before image pull/migration/application replacement. No gate was lowered.
  The deployment job synchronized its normal deployment scripts before preflight;
  application containers and database contents were not replaced by this release.
- Read-only status [40700200118](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40700200118)
  at 13:50 UTC / 20:50 GMT+7 reported `/dev/root` **29G total, 28G used,
  770M available, 98% used**. The old deployed commit remains
  `7ce3bc4df525669b9a49ba3edd3b425fd16b470c`; Spring/RAG and dependent health-checked
  services were healthy and Spring returned `UP`.
- Browser MEMBER check: the PostgreSQL title displayed an **active ebook loan**
  and a Read ebook link. Opening it reached the secure reader, but it displayed
  `Private PDF delivery is not available yet`, consistent with the old backend.
  This is not a PDF-rendering, checksum, citation-navigation or answer PASS.
- Provider-free helper validation [40659356110](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40659356110)
  succeeded on the **old** runtime: all 35/35 Qdrant points matched. Its candidate
  `b85f25f448db1cb448ad2fe42827a346d65e1e0b4189ddd0560cd83586d3287f`
  is diagnostic only, **not** the candidate for the new image. No generation,
  embedding or held-out request was made by this plan.

Next: obtain owner direction for capacity recovery (inspect Docker disk usage
and review exact unused-image/cache targets, or expand the Azure disk). Do not
delete code, database/PDF/vector volumes, backups, running images or the recorded
rollback images. Do not run a broad `docker system prune -a --volumes`.
After sufficient capacity, rerun the failed deployment job for the same scanned
candidate, verify health, then create/review a **new-image** provider-free plan
before the full held-out pass. A complete dev answer batch remains separate.

## Capacity recovery approved and executed: 21:46 GMT+7

The owner subsequently approved cleanup of unused Docker cache/images, preserving
code, volumes/databases/PDFs/vectors, backups and current/rollback images.

- Inventory [40707494373](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40707494373)
  found 17 old unreferenced Library images; build cache was 0 B.
- Maintenance [PR #43](https://github.com/phandinhphuc1234/SE330_BE/pull/43)
  helper `954ed5abb80fcd36f27451bdfde3d5d299be8642` passed
  [CI 40708466382](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40708466382):
  360 Java / 822 RAG tests, including seven mocked-Docker Linux shell safety tests.
- Cleanup [40709193750](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40709193750)
  removed exactly those 17 local Library image references without force/broad
  pruning. All container/rollback image IDs survived re-inspection; Spring was UP.
  No code, volume, backup or data deletion, container restart or image-registry
  deletion occurred. Removed images can be re-pulled from their GHCR references.
- Available host space increased **769M -> 4.0G**, usage **98% -> 86%** (`df -h`);
  approximately 3.2 GiB recovered. Docker now reports only 589 MB reclaimable
  images and zero build cache. Further Docker cleanup cannot reach 8 GiB.

This supersedes the earlier 770M free-space checkpoint, but the release remains
blocked below the 8 GiB safety requirement. No deployment rerun, new candidate
answer run or held-out query was made. Next requires owner direction for Azure
disk expansion or separately reviewed non-Docker cleanup; do not lower the gate.

## Full host check located SeaweedFS preallocation

Following owner authorization to inspect the whole host, read-only runs
40719995210, 40720718094 and 40721311754 found the capacity issue inside the
**active SeaweedFS persistent volume**, not log/cache growth or orphaned images.
The volume occupies 18,254,061,568 bytes. Seventeen `.dat` files each have about
1 GiB physically allocated while their combined logical length is only 806,056
bytes (some files are eight-byte empty volume headers). Version-pinned SeaweedFS
3.85 source confirms KEEP_SIZE preallocation. No volume/data file was deleted,
truncated, copied over or otherwise changed; no service was restarted.

The next safe path is an explicitly approved, tested storage maintenance window:
consistent backup/checksums, pause the storage writer, reclaim only allocation
beyond logical EOF while preserving data, then verify object/PDF bytes and health.
This may avoid growing the Azure disk, but is a proposal, **not an executed fix**.
The 8 GiB deployment requirement and remaining PDF/citation/held-out gates remain.

## Preserved intermediate failures

- Main release run 40655814473 passed tests and backend image scan but was blocked
  by the RAG image scan: NLTK 3.10.3, GHSA-j456-xh4h-cpf2 (HIGH; fixed 3.10.4).
  Deployment was skipped; existing containers were not replaced. A minimal
  dependency follow-up is required, not a scanner exception.
- Provider-free plan run 40658080486 failed before Python started because SSH
  omitted the empty final candidate argument (`$6` unset). The remote command now
  uses an explicit safe default, with a regression assertion. No generation,
  embedding or held-out query ran in this failed attempt.
