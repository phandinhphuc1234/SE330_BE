# RAG-P0-07 Retrieval and Answer Evaluation Evidence — 2026-10-18

> Historical checkpoint. On 2026-10-24 the completed full v2 report from
> 2026-10-19 (`rag-eval-20261019T072231556760Z`) was verified: 20/20, gate PASS.
> See [latest local release gates](2026-10-24-rag-local-release-gates.md).
> The failed/partial reports below are preserved, not relabeled as passing.

Branch: `codex/rag-v1-production-readiness`
Baseline commit: `0e974ae317c5383b1183de09025091feb291a3f2`

## Retrieval-only run — PASS

Report files:

- `app/evaluation/reports/rag-eval-20261018T040156Z.json`
- `app/evaluation/reports/rag-eval-20261018T040156Z.md`

The full 20-case dataset ran at top K 3 with no answer/provider calls:

| Mode | HitRate@3 | Recall@3 | MRR | nDCG@3 | Mean latency | Errors | Scope violations |
|---|---:|---:|---:|---:|---:|---:|---:|
| dense | 0.882 | 0.853 | 0.804 | 0.812 | 660.9 ms | 0 | 0 |
| hybrid | 0.941 | 0.912 | 0.725 | 0.762 | 773.3 ms | 0 | 0 |

This reproduces the recorded baseline and passes the configured hybrid hit-rate
and recall gates without changing thresholds or weights.

## Full answer run — PARTIAL, BLOCKED BY PROVIDER

Report files:

- `app/evaluation/reports/rag-eval-20261018T040732Z.json`
- `app/evaluation/reports/rag-eval-20261018T040732Z.md`

The run used hybrid retrieval, top K 3, no LLM judge and a 15-second shared LLM
request interval. It attempted all 20 golden cases.

Observed result:

- 18 answers completed.
- 2 answer calls failed with HTTP `502` carrying the sanitized code
  `LLM_RATE_LIMITED`.
- The failed cases were `unknown-city` and `unknown-maiden-name`.
- All 17 answerable cases completed without abstaining.
- The one completed unanswerable case abstained.
- Scope violations: `0`.
- Hybrid retrieval metrics remained unchanged from the passing retrieval run.
- Metrics over the incomplete run: abstention accuracy `0.900`, citation
  precision `0.902`, citation recall `0.912`, lexical answer-point coverage
  `0.765`.

The runner correctly counted provider failures as operational errors. It did
not convert them into successful abstentions or remove them from denominators.

## Bounded retry — still provider-blocked

After a cooldown, only the two failed case IDs were retried with a 30-second
LLM interval. The retry report is:

- `app/evaluation/reports/rag-eval-20261018T041017Z.json`
- `app/evaluation/reports/rag-eval-20261018T041017Z.md`

Both calls again returned `LLM_RATE_LIMITED`. No prompt, retrieval threshold,
dataset label or expected result was changed to hide the failure.

## Conclusion

Retrieval evaluation passed. Answer evaluation is accurately classified as
`PARTIAL — BLOCKED_BY_PROVIDER`: 18/20 completed and the two remaining cases
require valid Gemini quota before this P0 gate can be marked done. The partial
report is useful operational evidence but is not a passing quality baseline.

## Executable retrieval gate rerun

`rag-eval-20261018T070921557652Z.json/.md` reran all 20 cases with
`--modes dense hybrid --gate-modes hybrid --min-hit-rate 0.94 --min-recall 0.91`.
Exit code was 0 and `qualityGate.passed=true`. Hybrid retained the same metrics;
dense was retained as the comparison arm. The earlier CLI applied 0.94 to both
modes, so its hybrid metrics met the intended gate but the aggregate CLI exit
did not; the new mode-specific gate resolves that mismatch explicitly.

The new report records base commit `0e974ae`, dirty worktree true, observed
embedding `gemini-embedding-2-3072-v1`, and chunking `v1`.

## Later answer diagnostic and prompt change

The bounded two-case run `rag-eval-20261018T071939100580Z.json/.md` eventually
completed both calls without provider errors. `unknown-maiden-name` abstained;
`unknown-city` said the city was not explicitly stated but set `abstained=false`.
The strict gate correctly failed `hybrid:abstentionMismatch` (accuracy 0.5 on
this two-case subset). This was a response-contract failure, not an invented
city name and not a passing full dataset run.

Prompt `library-ebook-answer-v2` now explicitly requires abstention when the
requested fact is absent; a topic mention or a “not stated” answer cannot be
marked as a grounded answer. A regression verifies this general rule. No
golden label, threshold or case-specific keyword was changed.

The user requested completing code/tests and keeping deployment waiting for
quota. Full 20-case live evaluation of prompt v2 is therefore pending. Previous
reports remain intact and cannot be reused as evidence that v2 has passed.
