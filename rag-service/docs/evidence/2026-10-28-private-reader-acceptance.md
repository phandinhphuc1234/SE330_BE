# Production private reader and PostgreSQL acceptance - 2026-10-28

Status: IN PROGRESS. Do not infer production acceptance from CI or merge.

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

- [ ] Backend merge, main CI, image scan and healthy deployment.
- [ ] Unchanged PostgreSQL PDF loads through private reader API.
- [ ] File/range/HEAD and missing/foreign/closed-session checks in production.
- [ ] Browser citation opens the expected PDF page and unsupported question abstains.
- [ ] Frozen candidate, complete dev answer run and first held-out evaluation;
  preserve provider failures and report lexical metrics separately from semantic review.

No new PostgreSQL answer or held-out result has been measured at this checkpoint.

## Preserved intermediate failures

- Main release run 40655814473 passed tests and backend image scan but was blocked
  by the RAG image scan: NLTK 3.10.3, GHSA-j456-xh4h-cpf2 (HIGH; fixed 3.10.4).
  Deployment was skipped; existing containers were not replaced. A minimal
  dependency follow-up is required, not a scanner exception.
- Provider-free plan run 40658080486 failed before Python started because SSH
  omitted the empty final candidate argument (`$6` unset). The remote command now
  uses an explicit safe default, with a regression assertion. No generation,
  embedding or held-out query ran in this failed attempt.
