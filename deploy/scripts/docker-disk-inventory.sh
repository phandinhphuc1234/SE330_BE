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

# Host capacity diagnostics: sizes/layout only, never file contents or secrets.
# sudo is used only when already available non-interactively for read-only tools.
reader=()
if command -v sudo >/dev/null && sudo -n true 2>/dev/null; then reader=(sudo -n); fi
printf '[host-disk] Block devices and filesystem types\n'
lsblk -b -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINTS
df -hT
printf '[host-disk] Top-level allocation in bytes (same filesystem only)\n'
"${reader[@]}" du -x -B1 --max-depth=1 / 2>/dev/null | sort -n || true
printf '[host-disk] Selected system/application directory allocation\n'
for dir in /var/lib /var/log /var/cache /home /opt /tmp /var/tmp /usr /snap; do
  if [[ -d "$dir" ]]; then
    "${reader[@]}" du -x -B1 --max-depth=1 "$dir" 2>/dev/null | sort -n || true
  fi
done
printf '[host-disk] Docker container log sizes only (no log contents)\n'
for container in $(docker ps -aq); do
  log_path=$(docker inspect --format '{{.LogPath}}' "$container")
  [[ "$log_path" == /var/lib/docker/containers/*/*-json.log ]] || continue
  "${reader[@]}" stat --format='%s %n' -- "$log_path" 2>/dev/null || true
done
printf '[host-disk] Journal allocation\n'
"${reader[@]}" journalctl --disk-usage 2>/dev/null || true
printf '[host-disk] Large log/temp files: metadata only\n'
"${reader[@]}" find /var/log /var/tmp /tmp -xdev -type f -size +200M -printf '%s %p\n' 2>/dev/null | sort -n || true
printf '[host-disk] Open deleted files, when lsof is installed\n'
if command -v lsof >/dev/null; then "${reader[@]}" lsof -nP +L1 2>/dev/null || true; fi
printf '[docker-store] Active storage driver and status\n'
docker info --format '{{.Driver}} {{json .DriverStatus}}'
printf '[docker-store] Docker/containerd subdirectories in bytes\n'
for dir in /var/lib/docker /var/lib/containerd; do
  "${reader[@]}" du -x -B1 --max-depth=1 "$dir" 2>/dev/null | sort -n || true
done
printf '[docker-store] Container volume mounts (no config/environment)\n'
for container in $(docker ps -aq); do
  docker inspect --format '{{.Id}} {{json .Mounts}}' "$container"
done
printf '[docker-store] Known volume names and mountpoints (no driver options)\n'
for volume in $(docker volume ls -q); do
  docker volume inspect --format '{{.Name}} {{.Mountpoint}}' "$volume"
done
printf '[docker-store] Large volume files: size and path only, no contents\n'
"${reader[@]}" find /var/lib/docker/volumes -xdev -type f -size +200M -printf '%s %p\n' 2>/dev/null | sort -n || true
printf '[seaweed-capacity] Largest allocated files: 512-byte blocks, logical bytes, path only\n'
"${reader[@]}" find /var/lib/docker/volumes/quanlythuvien_rag_seaweedfs_data -xdev -type f \
  -printf '%b %s %p\n' 2>/dev/null | sort -n | tail -n 20 || true
printf '[seaweed-capacity] Live software defaults for allocation (help only)\n'
seaweed_ids=$(docker ps -q --filter label=com.docker.compose.project=quanlythuvien --filter label=com.docker.compose.service=rag-seaweedfs)
if [[ -n "$seaweed_ids" && "$seaweed_ids" != *$'\n'* ]]; then
  docker exec "$seaweed_ids" weed version
  docker exec "$seaweed_ids" weed server -h 2>&1 | grep -A 2 -E 'prealloc|volumeSizeLimit|volume.max' || true
fi
printf '[docker-store] Mounted overlay targets\n'
findmnt -rn -t overlay -o TARGET || true
printf '[docker-store] Legacy Docker image/overlay metadata sizes\n'
for dir in /var/lib/docker/image /var/lib/docker/overlay2 /var/lib/docker/volumes /var/lib/docker/containers /var/lib/docker/buildkit; do
  if "${reader[@]}" test -d "$dir"; then
    "${reader[@]}" du -x -B1 --max-depth=1 "$dir" 2>/dev/null | sort -n | tail -n 12 || true
  fi
done
