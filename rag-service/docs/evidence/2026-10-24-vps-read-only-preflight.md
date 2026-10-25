# VPS read-only preflight — 2026-10-24

Purpose: confirm the owner's restarted Azure VPS is reachable before publishing
the local RAG/reborrow candidate. This is **not a new build/deploy acceptance**.

Source: [diagnostic workflow, successful](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40107594302).
It ran with `diagnostic_only=true`, pinned SSH host verification, and skipped the
asset-sync/start-containers step. No secret values were read into this document.

Observed:

- Public `https://api-library.flashsale123.tech/healthz` returned `status=UP`.
- VPS memory: 7882 MiB total, 5996 MiB available at that snapshot.
- Root disk: 29 GiB total, 13 GiB free (56% used).
- Spring, RAG API, PostgreSQLs, Redis instances, Qdrant and SeaweedFS reported
  healthy. RAG worker was running; it has no Docker healthcheck claim here.
- `RAG_ENABLED=true`, retrieval hybrid, chunking v1, Gemini Embedding 2 with
  3072 dimensions, generation `gemini-2.5-flash`.
- Gemini and internal RAG credentials were reported configured (presence only).
- Current deployed commit and remote main remain
  `0e974ae317c5383b1183de09025091feb291a3f2`.
- Repository `CD_ENABLED=true`: a successful main pipeline can deploy production.

No restart, runtime env edit, source upload, migration, database write or provider
call was performed by this diagnostic. Production RAG document/index contents,
authorized Ask/citation, new-code reborrow behavior and rollback digests have
not been verified by this workflow.

The owner requested a VPS build/test on 24/10. Since an earlier instruction
explicitly kept Git changes local, a clarification was requested: approve
commit/push/PR/merge after CI for the normal image-based release, or provide a
separate isolated VPS-test route without publishing/changing production. The
current pipeline deliberately builds on CI, not directly on the production VPS.
The owner subsequently approved commit/push/PR/merge/deploy **only when CI passes**.
The publishing hold is therefore lifted for this release; this preflight still
does not prove that a deployment has happened. The owner clarified the objective
is a full RAG test with measured CV evidence, not editing a CV file.

Do not claim the new release was deployed until the release/test steps complete.
