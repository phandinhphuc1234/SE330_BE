# RAG-P0-09 — Local quality gates

> Updated checkpoint: [2026-10-24 local release gates](2026-10-24-rag-local-release-gates.md).
> Full v2 evaluation now has a verified 20/20 PASS report from 19/10; real
> adversarial PDF/browser checks passed 24/10. A newly approved ebook reborrow
> fix is being validated separately. Historical test/scan counts below are not
> evidence of that newer Java fix; CI/production remain pending.

Branch: `codex/rag-v1-production-readiness`. Baseline commit:
`0e974ae317c5383b1183de09025091feb291a3f2` plus the current working changes.
Date: 2026-10-18. Production release remains on hold by user request.

**Latest follow-up:** [remaining local validation](2026-10-18-rag-p0-remaining-local-validation.md)
supersedes the earlier local counts/image below: 803 RAG tests, 88% coverage,
rollback failure-state fix, new real negative smokes, and rebuilt image scan PASS.
Live prompt-v2 evaluation completed 19/20; the final case hit provider quota.
The following sections retain the earlier checkpoint as historical evidence.

## Tests

Final RAG suite, after prompt v2 and observability changes:

```text
python -m pytest --cov=app --cov-report=xml --cov-report=term -q
792 passed in 190.08s
TOTAL: 5924 statements, 752 missed, 87% coverage
```

No failure or skip was reported. The project virtualenv was synchronized with
the lock file; this final run did not emit the alias warnings seen in the
earlier, unsynchronized baseline environment.

Backend suite, including the new trace-propagation test:

```text
.\mvnw.cmd test
Tests run: 320, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Maven took 11 minutes while multiple local services were running. Surefire
reported its existing delayed JVM shutdown after `System.exit(0)`; the process
returned exit 0 and all tests passed. This is recorded rather than suppressed.

## Runtime and configuration

- Full public PDF upload for a separate local fixture: HTTP 201 → job 4
  INDEXED; document 2 has 6 SQL chunks and 6 scoped active Qdrant points.
- Public reader smoke: anonymous 401, authorized search 3 hits, correct
  book/ebook/page scope, signed PDF 6 pages / 86,822 bytes, session closed.
- Spring trace ID reached RAG logs unchanged in the runtime check.
- `/metrics` returned HTTP 200 and included persisted worker ingestion stats.
- Prometheus reported all four configured targets up: Spring, RAG, Qdrant and
  Prometheus itself.
- `promtool check config` accepted the merged monitoring config.
- Spring health is UP after local Compose adopted the existing optional SMTP
  health policy from production. No SMTP delivery claim is made.
- `git diff --check` passed.

## Image build and security scan

Final local RAG candidate:

```text
sha256:09093c39701d1d5ad6a0a82272f3babbed63b9ecaf2b124ac6c08552427a339e
```

The candidate was built from the current app source. A runtime import confirmed
`PROMPT_VERSION=library-ebook-answer-v2` and the persisted ingestion collector
includes `CHUNKED` as a recognized status.

Trivy 0.74.0 scan, using the same severity/fix policy as CI:

```text
--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1
exit code 0
reported fixed HIGH/CRITICAL vulnerabilities: 0
```

Local raw artifact: `docs/evidence/2026-10-18-trivy-rag-candidate.json`.
Earlier scan artifacts are also preserved under their own filenames. The
result means the configured gate passed, not that all possible vulnerabilities
or unfixed issues are absent. The local image digest is not a production
deployment or published GHCR digest.

## Review and remaining release gates

Reviewed changes are confined to RAG observability/evaluation/prompt tests,
Spring RAG trace forwarding, local health/monitoring configuration, and their
documentation. No migration, retriever weight, embedding policy, golden label
or frontend behavior was changed. Existing untracked historical reports remain
preserved.

Full prompt-v2 answer evaluation is still pending. The last v1 diagnostic
correctly failed an abstention-contract case; it is preserved as failed
evidence. The prompt change is covered by deterministic regression tests but
has not been declared a live model-quality pass.

CI verification, production rollback target capture, merge, immutable release
and post-deploy browser checks have not been performed for these working
changes. User direction: complete code/tests first and keep deployment waiting
for quota. Therefore P0-09 is **LOCAL PASS / CI PENDING**, and the overall plan
remains partial.
