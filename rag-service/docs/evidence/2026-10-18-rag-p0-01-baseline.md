# RAG-P0-01 Baseline Evidence — 2026-10-18

Captured at: `2026-10-18 10:24:04 +07:00`
Branch: `codex/rag-v1-production-readiness`
Baseline commit: `0e974ae317c5383b1183de09025091feb291a3f2`

## Repository state before implementation

- `main` matched `origin/main` at the baseline commit.
- Existing untracked evaluation reports were present under
  `rag-service/app/evaluation/reports/`; they were not modified or deleted.
- The production completion plan was the only other new file before this
  evidence record was added.

## Local toolchain

| Tool | Detected version |
|---|---|
| Git | 2.55.0.windows.4 |
| Docker | 29.7.2 |
| Docker Compose | v5.5.0 |
| Maven wrapper | 3.9.14 |
| Maven wrapper Java | Eclipse Adoptium JDK 21.0.10 |

The system `java` command points to Java 8, but `mvnw.cmd` resolves JDK 21.
Backend verification must therefore use the Maven wrapper rather than assuming
the system Java runtime.

## Compose baseline

No project containers were running when the baseline was captured.

The merged local stack declares these services:

- `postgres`
- `redis`
- `library-service`
- `rag-postgres`
- `rag-redis`
- `rag-qdrant`
- `rag-seaweedfs`
- `rag-seaweedfs-init`
- `rag-migrate`
- `rag-api`
- `rag-worker`

The RAG dependencies have explicit healthchecks and `rag-api`/`rag-worker`
depend on completed migrations plus healthy Redis, Qdrant and object storage.

## Safe configuration audit

Only variable names and presence were inspected. No value was printed.

Required provider/infrastructure secrets present and non-empty locally:

- `RAG_INTERNAL_API_KEY`
- `GEMINI_API_KEY`
- `POSTGRES_PASSWORD`
- `RAG_POSTGRES_PASSWORD`
- `OBJECT_STORAGE_ACCESS_KEY`
- `OBJECT_STORAGE_SECRET_KEY`

Explicit baseline settings in the root `.env` include embedding provider/model,
embedding dimension, LLM provider/model, chunk size/overlap and hybrid weights.

These settings currently use application defaults rather than explicit root
`.env` values:

| Setting | Effective application default |
|---|---|
| `CHUNKING_STRATEGY_VERSION` | `v1` |
| `RETRIEVAL_MODE` | `hybrid` |
| `ANSWER_RETRIEVAL_TOP_K` | `6` |
| `ANSWER_SCORE_THRESHOLD` | `0.60` |
| `ANSWER_MAX_CONTEXT_CHARS` | `12000` |

Using defaults is valid for the current baseline. Before production verification,
the effective values must be printed by name/value only for non-secret settings
or obtained from container configuration without exposing secrets.

## Baseline conclusion

`RAG-P0-01` passed for the local environment:

- repository commit and dirty state were recorded;
- toolchain is available;
- Compose configuration resolves;
- required local secret variables are present;
- no service health claim was made because the stack had not been started yet.

Runtime health belongs to `RAG-P0-03` and later smoke-test tasks.
