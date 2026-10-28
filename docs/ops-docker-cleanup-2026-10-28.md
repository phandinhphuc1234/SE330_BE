# VPS Docker capacity recovery - 2026-10-28

Owner explicitly approved removing unused Docker cache/images while preserving
code, databases/PDFs/vectors, volumes, backups, running images and rollback images.

## Executed evidence

- Inventory: [40707494373](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40707494373).
  Found 17 old, unreferenced Library images and **0 B build cache**.
- Exact cleanup helper `954ed5abb80fcd36f27451bdfde3d5d299be8642` passed
  [CI 40708466382](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40708466382):
  **360 Java tests, 822 RAG tests** (includes seven Linux shell safety tests;
  one existing Python warning), CodeQL and SonarCloud.
  These are offline/CI tests, not RAG answer evaluation.
- Cleanup: [40709193750](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40709193750),
  succeeded at 14:46 UTC / 21:46 GMT+7. Exactly **17** reviewed image references
  were untagged/deleted locally using `docker image rm --no-prune`, without
  force removal. No broad prune, container/volume/file deletion or restart ran.

| Host filesystem | Before | After |
| --- | --- | --- |
| Total | 29G | 29G |
| Used | 28G | 25G |
| Available | 769M | 4.0G |
| Used percentage | 98% | 86% |

Values are human-readable `df -h` observations, not exact byte measurements.
About 3.2 GiB was recovered. Remaining Docker allocation: nine images, eight
active, 3.936 GB total, 589 MB reclaimable; build cache remains zero.
Removing the remaining unused image would still not reach 8 GiB, so it was kept.

All container-referenced and protected image IDs were re-inspected successfully
after removal. Spring returned `UP`. Current rollback images remain:

- Backend `ghcr.io/phandinhphuc1234/se330-be@sha256:c2a4630a553224b87402b4d5f2ac32c615f5dab503c6e7c19af683c958bd1562`.
- RAG `ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e`.

Deleted image prefixes (inventory identities; not new guessed targets):
`3fdc5421 c76360b5 0018ed48 bddeb0ac d119af50 b0f69bf6 ec5a647a 98baa8c5
7b777740 759690b9 5291ee0c 752091c8 3421ced9 bb4fbb34 620eae16 69d4d335 ef8f9111`.
Removal affected the VPS image cache only, not GHCR. Registry references remain
in the run logs and can be pulled again if needed; no code/data restore is needed.

## Safety and remaining blocker

The one-shot script first verifies unchanged recorded deployment, acquires the
deployment lock, resolves every reviewed prefix uniquely to a full immutable ID,
checks all running/stopped container references and recorded rollback IDs, and
validates Library-only registry references **before any removal**. It requires
exact-revision PR CI and pinned SSH. It intentionally refuses a repeated run
once targets are missing; do not weaken the checks to repeat it.

Application deployment is still blocked by the existing **8 GiB free** preflight.
Do not lower that guard. No new PDF-rendering, citation, answer or held-out PASS
is claimed. Next action needs owner direction: increase Azure disk capacity or
inspect non-Docker usage read-only and review any separate cleanup targets.
No automatic billable disk expansion or deletion outside Docker was performed.
