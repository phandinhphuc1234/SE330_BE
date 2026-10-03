# RAG Deployment Notes

## Compose files

| File | Role |
| --- | --- |
| `infra/compose/standalone/compose.yml` | Archived standalone definition for API, worker, beat, migration and RAG infrastructure. |
| `infra/compose/standalone/compose.override.yml` | Standalone runtime defaults: no reload for API, restart policies for long-running services. |
| `infra/compose/standalone/compose.observability.yml` | Standalone RAG PostgreSQL and Redis exporters only. |
| `../compose.yaml` + `../compose.rag.yaml` | Preferred integrated library and RAG stack. |

## Required secret

```dotenv
RAG_INTERNAL_API_KEY=<long-random-secret>
```

Spring Boot must use the same value in `X-RAG-API-Key`. Do not commit real secrets or log this header.

For production, inject secrets from the deployment platform and prefer TLS/mTLS or short-lived service identity in addition to network isolation.

## Networks

Create the cross-repository network once:

```bash
docker network create library-platform-net
```

RAG dependencies use `rag-internal-net`. Only integration-facing services join `library-platform-net`.

## Startup order

Use the repository-level PowerShell helper for the preferred integrated stack:

```powershell
.\scripts\dev-up.ps1
```

See `infra/compose/standalone/README.md` only when RAG must run independently.

The `beat` service is optional and currently schedules placeholder tasks only.
Start it later through `.\scripts\dev-up.ps1 -ScheduledJobs` after real
cleanup/reconciliation tasks are implemented.

Optional exporters:

The archived standalone exporter command is documented in
`infra/compose/standalone/README.md`.

## Migration warning

Migration `004_internal_service_schema` intentionally drops legacy RAG-local tables:

```text
users
workspaces
chat_sessions
chat_messages
feedback
```

Back up them before migration if they contain data that must be retained. Spring Boot becomes the source of truth for those concerns.

## Host exposure

Spring Boot reaches RAG through `http://rag-api:8000` on the shared network.

If you want to avoid host exposure during local testing, run only the base file explicitly and control published ports by the compose command you choose for that environment.

Swagger/OpenAPI remains disabled unless:

```dotenv
ENABLE_API_DOCS=true
```

## Health and verification

From the host in development mode:

```bash
curl http://localhost:8000/api/v1/health
```

From the Spring Boot container:

```bash
curl http://rag-api:8000/api/v1/health
curl http://rag-seaweedfs:8333
```

Check networks and buckets:

```bash
docker network inspect library-platform-net
aws --endpoint-url http://localhost:8333 s3 ls
```

Expected buckets:

```text
library-private
library-temp
rag-artifacts
```

## Production requirements

- Do not publish PostgreSQL, Redis, Qdrant or SeaweedFS management ports publicly.
- Terminate TLS at a trusted ingress/service mesh or use mTLS directly.
- Rotate the service credential and storage credentials.
- Back up RAG PostgreSQL, Qdrant and SeaweedFS.
- Run migration once per release, not in every API replica.
- Add FastAPI metrics before enabling the `rag-api` Prometheus target.
- Scale Celery workers by queue depth; run only one beat scheduler.
