# Archived standalone RAG Compose stack

These files preserve the original RAG-only development workflow. They remain
executable and are not used by the repository-level `compose.yaml` plus
`compose.rag.yaml` workflow.

Run from the repository root:

```powershell
docker compose --project-directory rag-service `
  --env-file rag-service/.env `
  -f rag-service/infra/compose/standalone/compose.yml `
  -f rag-service/infra/compose/standalone/compose.override.yml `
  up -d
```

Add exporters by also passing:

```text
-f rag-service/infra/compose/standalone/compose.observability.yml
```

Prefer `scripts/dev-up.ps1` for normal integrated development.
