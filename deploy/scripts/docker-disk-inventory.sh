#!/usr/bin/env bash
# Read only: no environment dump, prune, restart, volume access or application call.
set -Eeuo pipefail
printf '[disk] Host filesystem\n'
df -h "$HOME"
printf '[disk] Docker root\n'
docker info --format '{{.DockerRootDir}}'
printf '[disk] Docker allocation and reclaimability\n'
docker system df -v
printf '[disk] Every container image reference (including stopped containers)\n'
docker ps -a --no-trunc --format '{{.ID}} | {{.Names}} | {{.Image}} | {{.Status}}'
printf '[disk] Library image references\n'
docker image ls --digests --no-trunc --format '{{.Repository}} | {{.Tag}} | {{.Digest}} | {{.ID}} | {{.Size}}' \
  --filter reference='ghcr.io/phandinhphuc1234/se330-*'
printf '[disk] Recorded current/rollback image references\n'
for name in current-image current-rag-image previous-image previous-rag-image; do
  path="$HOME/apps/quanlythuvien/state/$name"
  if [[ -f "$path" ]]; then
    printf '%s: ' "$name"
    head -n 1 "$path"
  fi
done
