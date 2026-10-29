# VPS Docker capacity recovery - 2026-10-28

Updated 2026-10-29: capacity recovery and the scanned application deployment
succeeded. Earlier blockers below are historical checkpoints, not current status.

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

## Full read-only host investigation: root cause located

The owner next requested a complete capacity check and a safe deployment path.
Read-only runs [40719995210](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40719995210),
[40720718094](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40720718094)
and [40721311754](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40721311754)
inspected filesystem layout, directory allocation, log sizes, volume mounts and
file allocation metadata. No file contents, runtime secrets or PDF bytes were
printed; no cleanup/restart/storage rewrite was performed in these runs.

| Allocation | Observed bytes | Interpretation |
| --- | ---: | --- |
| `/usr` | 2,607,124,480 | OS/runtime files; not cleanup targets |
| `/var/log` | 164,302,848 | Includes about 147 MiB journal; not the main problem |
| `/var/cache` | 164,712,448 | Too small to solve the capacity gap |
| `/var/lib/containerd` | 3,943,575,552 | Current image content/snapshots |
| `/var/lib/docker/volumes` | 18,378,493,952 | Persistent volumes, not disposable image cache |
| SeaweedFS volume | 18,254,061,568 | Almost all persistent-volume allocation |

Docker is using `overlayfs` with `io.containerd.snapshotter.v1`. There is no
large legacy overlay2 cache here. A read-only intermediate run 40720457456 failed
because modern container inspection omits GraphDriver; switching diagnostics
to the supported Mounts field resolved that observation failure. It did not
change the running service.

The exact source is **17 SeaweedFS `.dat` files**, each allocated 2,097,160
512-byte blocks (**1 GiB + 4 KiB**) although their logical lengths are only
8-368,912 bytes. Their combined logical length is **806,056 bytes**, versus
**18,253,680,640 allocated bytes**. This is preallocated space beyond EOF,
not 18 GB of uploaded PDF content. Both empty and nonempty files are present;
none should be deleted based on apparent emptiness.

Live SeaweedFS is version 3.85 (`7d7e06681`). Its Linux volume creation code uses
`fallocate` with KEEP_SIZE when preallocation is requested, explaining why
logical-length-based tools differ from actual disk allocation:
[version-pinned implementation](https://github.com/seaweedfs/seaweedfs/blob/3.85/weed/storage/backend/volume_create_linux.go).

### Proposed recovery, not executed

Do not manually remove SeaweedFS files or its volume. Request approval for a
short storage maintenance window. Prepare a tested operation that pauses the
writer, takes a consistent private volume backup and checksums, then reclaims
only beyond-EOF preallocation without changing logical file bytes. Verify
whole-file/index metadata and S3 object/PDF checksums before restarting normal
access. Preserve the backup and fail closed on any checksum mismatch. Explicitly
keep future preallocation disabled and check the live flags before changing them.

This can potentially recover most of 17 GiB without a billable disk expansion,
but recovery, post-start health and sufficient free space are **not yet verified**.
Only then retry the existing scanned release and continue reader/held-out tests.

## Approved maintenance completed on 29/10

The owner explicitly approved the short backed-up storage maintenance and deploy.
Final helper `5cff07f00177347ad381cb009171c4895739d8cd` passed
[CI 40804173170](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40804173170):
360 Java / 830 Python/RAG tests, CodeQL and SonarCloud. Eight new Linux allocation,
backup, fault and transport regression cases supplement the seven Docker guards.

First attempt [40803910885](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40803910885)
stopped before storage pause or data mutation: a root-owned copied verifier was
unreadable to the non-root RAG process. The trap resumed the idle worker. Read-only
status 40804177322 confirmed worker running, SeaweedFS healthy and Spring UP.
The verifier now streams through stdin; no permissions were weakened.

Successful maintenance [40804673423](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40804673423)
at 02:10-02:11 UTC / 09:10-09:11 GMT+7:

- Verified private logical-volume backup: **114 regular files**, archive SHA-256
  `099b1e7cd45e26e084323e6b7edf280cfb66d07e680b6c80def65a2de6bb56b9`.
  Retained on VPS under `backups/quanlythuvien/seaweed-20261029-WVzqIH/`.
  This is an operation-recovery backup, not a separately verified off-host DR copy.
- Reclaimed **18,252,746,752 bytes** (about 17 GiB) by same-length ftruncate of
  exactly 17 reviewed files. Allocation **18,253,680,640 -> 933,888 bytes**.
  Logical sizes, every whole-file checksum, ownership, mode and modification
  timestamps matched. No file/volume was deleted, renamed or logically shortened.
- Before/after S3 verification matched **18 objects / 919,756 bytes**, including
  PostgreSQL source PDF SHA-256 `73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26`.
  Object keys/bytes/credentials and the private file manifests remain on the VPS.
- SeaweedFS and worker resumed; Spring UP. Available host space **4.0G -> 21G**,
  usage **86% -> 26%** immediately after maintenance. The 8 GiB gate was unchanged.

## Release deployed and browser smoke verified

Reran only the previously failed deployment job of scanned release
[40698306283, attempt 2](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40698306283).
It succeeded at 02:15 UTC / 09:15 GMT+7, source
`d94f24e3fec8cb3d624663cd12b23a4ce9cf8f9a`, Flyway installed rank 48, public health UP.

- Backend image `ghcr.io/phandinhphuc1234/se330-be@sha256:53fdf974f8a2cfb750aeddd193e5eb349eaacb3ccacb7149c12ccca9a497b40a`.
- RAG image `ghcr.io/phandinhphuc1234/se330-rag@sha256:3f978ac7617e80895abb62635fe598af51ce24172560c33e6d3955bb37d62197`.
- Read-only post-release [40806301485](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/40806301485)
  verified new commit/image prefixes and health; **20G free / 31% used** after pulls.
- MEMBER browser loaded the unchanged 22-page PDF. A development smoke question
  returned Read Committed with page-5 evidence; clicking citation changed the
  reader to **5/22** and the visible page contains the supporting statement.
  A Redis/TTL out-of-corpus question abstained with no evidence presented.
- Anonymous PDF GET/HEAD/Range each returned **401**. Authenticated range/HEAD,
  missing/cross-book/closed-session negative production checks are still not run;
  their CI coverage must not be presented as production execution.

The original capacity blocker is resolved. Full answer/held-out evidence is
tracked separately in RAG PR #40; these two browser smokes are not a complete
development benchmark or a semantic accuracy score. PR #43 remains separate and
does not need to merge to preserve the already executed maintenance evidence.
