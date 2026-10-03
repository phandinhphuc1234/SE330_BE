# Configuration ownership

The repository contains two deployable applications: the Spring Boot library
API and the Python RAG service. Configuration files are grouped by runtime
instead of duplicating one complete environment per service.

## Local integrated stack

| File | Responsibility |
| --- | --- |
| `.env` | Real local values used for Compose interpolation. It is ignored by Git. |
| `.env.example` | Safe template for every value accepted by the integrated local stack. |
| `compose.yaml` | Spring Boot, library PostgreSQL and library Redis. |
| `compose.rag.yaml` | Optional RAG API, worker, migration, PostgreSQL, Redis, Qdrant and SeaweedFS overlay. |
| `scripts/dev-up.ps1` | Builds and starts the integrated stack. |
| `scripts/dev-down.ps1` | Stops containers without deleting named volumes. |
| `scripts/dev-logs.ps1` | Follows logs for all or selected services. |

Start the complete local stack:

```powershell
.\scripts\dev-up.ps1
```

Start it with the optional Celery Beat scheduler:

```powershell
.\scripts\dev-up.ps1 -ScheduledJobs
```

The browser calls Spring Boot. Spring Boot calls `http://rag-api:8000` through
Docker DNS. RAG infrastructure ports are bound to loopback for diagnostics and
must not be published directly on a production firewall.

## Standalone RAG development

`rag-service/.env` and `rag-service/.env.example` belong only to developers who
run the Python service outside the integrated repository Compose stack. The old
standalone Compose definitions are preserved under
`rag-service/infra/compose/standalone/`; they are not used by the normal library
development or production commands.

If an existing checkout still keeps RAG-only values in `rag-service/.env`, copy
missing values into the ignored root `.env` without printing secrets:

```powershell
.\scripts\config\migrate-rag-env.ps1
```

## Production

| File | Responsibility |
| --- | --- |
| `deploy/runtime.env.example` | Template used by provisioning on the VPS. |
| `/opt/quanlythuvien/config/runtime.env` | Real production values on the VPS; never committed. |
| `deploy/compose.production.yaml` | Production runtime definition. |
| `deploy/scripts/*.sh` | Provisioning, preflight, deployment and rollback behavior. |

Production does not load the repository root `.env` or `rag-service/.env`.
GitHub secrets are used only to establish deployment and provision the protected
runtime file; applications receive configuration through container environment
variables.

## Secret rules

- Never place an SSH private key in any `.env` file.
- Keep local SSH keys as dedicated `.pem` files protected by filesystem access
  controls.
- Keep `.env`, `rag-service/.env` and production `runtime.env` out of Git.
- Commit examples with placeholders, never real API keys, passwords or tokens.
- Use the same `RAG_INTERNAL_API_KEY` in Spring and RAG; never expose it to the
  frontend.

