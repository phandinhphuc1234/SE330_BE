# Production private reader and PostgreSQL acceptance - 2026-10-28

Status: BLOCKED BY VPS DISK CAPACITY. Do not infer production acceptance from CI or merge.

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
- [ ] Healthy backend/RAG deployment: blocked by insufficient VPS disk space.
- [ ] Unchanged PostgreSQL PDF loads through private reader API.
- [ ] File/range/HEAD and missing/foreign/closed-session checks in production.
- [ ] Browser citation opens the expected PDF page and unsupported question abstains.
- [ ] Frozen candidate, complete dev answer run and first held-out evaluation;
  preserve provider failures and report lexical metrics separately from semantic review.

No new PostgreSQL answer or held-out result has been measured at this checkpoint.

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

## Preserved intermediate failures

- Main release run 40655814473 passed tests and backend image scan but was blocked
  by the RAG image scan: NLTK 3.10.3, GHSA-j456-xh4h-cpf2 (HIGH; fixed 3.10.4).
  Deployment was skipped; existing containers were not replaced. A minimal
  dependency follow-up is required, not a scanner exception.
- Provider-free plan run 40658080486 failed before Python started because SSH
  omitted the empty final candidate argument (`$6` unset). The remote command now
  uses an explicit safe default, with a regression assertion. No generation,
  embedding or held-out query ran in this failed attempt.
