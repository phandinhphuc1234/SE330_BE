#!/usr/bin/env bash
# One-shot recovery approved 28/10/2026; targets from inventory run 40707494373.
# No broad prune, --force removal, container/volume/file deletion or service restart.
set -Eeuo pipefail
fail() { printf '[cleanup] ERROR: %s\n' "$*" >&2; exit 1; }
state_dir="${LIBRARY_MAINTENANCE_STATE_DIR:-$HOME/apps/quanlythuvien/state}"
[[ -d "$state_dir" ]] || fail 'Deployment state directory is missing.'
exec 9>"$state_dir/deploy.lock"
flock -n 9 || fail 'A deployment/maintenance operation is active.'

expected_be='ghcr.io/phandinhphuc1234/se330-be@sha256:c2a4630a553224b87402b4d5f2ac32c615f5dab503c6e7c19af683c958bd1562'
expected_rag='ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e'
[[ "$(<"$state_dir/current-image")" == "$expected_be" ]] || fail 'Backend changed; review a new inventory.'
[[ "$(<"$state_dir/current-rag-image")" == "$expected_rag" ]] || fail 'RAG changed; review a new inventory.'
protected_ids=()
for ref in "$expected_be" "$expected_rag"; do
  id=$(docker image inspect --format '{{.Id}}' "$ref") || fail 'Protected image cannot be inspected.'
  protected_ids+=("$id")
done
for name in previous-image previous-rag-image; do
  if [[ -f "$state_dir/$name" ]]; then
    id=$(docker image inspect --format '{{.Id}}' "$(<"$state_dir/$name")") || fail 'Rollback image cannot be inspected.'
    protected_ids+=("$id")
  fi
done
container_list=$(docker ps -aq) || fail 'Container inventory failed.'
container_ids=()
if [[ -n "$container_list" ]]; then mapfile -t container_ids <<< "$container_list"; fi
used_images=()
for container in "${container_ids[@]}"; do
  id=$(docker inspect --format '{{.Image}}' "$container") || fail 'Container image cannot be inspected.'
  used_images+=("$id")
done

# Eight-character prefixes are from the observed image inventory, not guessed IDs.
# Resolve uniquely to full immutable IDs and validate EVERY target before deletion.
reviewed=(3fdc5421 c76360b5 0018ed48 bddeb0ac d119af50 b0f69bf6 ec5a647a
          98baa8c5 7b777740 759690b9 5291ee0c 752091c8 3421ced9 bb4fbb34
          620eae16 69d4d335 ef8f9111)
targets=()
for prefix in "${reviewed[@]}"; do
  id=$(docker image inspect --format '{{.Id}}' "$prefix")
  [[ "$id" =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'Invalid/ambiguous image identity.'
  for protected in "${protected_ids[@]}" "${used_images[@]}"; do
    [[ "$id" != "$protected" ]] || fail "Reviewed target is in use/protected: $id"
  done
  mapfile -t refs < <(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$id" | sed '/^$/d')
  (( ${#refs[@]} > 0 )) || fail "No recoverable registry reference: $id"
  for ref in "${refs[@]}"; do
    [[ "$ref" =~ ^ghcr\.io/phandinhphuc1234/se330-(be|rag)@sha256:[a-f0-9]{64}$ ]] || fail 'Non-Library image reference found.'
    targets+=("$ref")
  done
done
printf '[cleanup] Approved unused Library references: %s\n' "${#targets[@]}"
printf '%s\n' "${targets[@]}"
printf '[cleanup] Before\n'
df -h "$HOME"
for ref in "${targets[@]}"; do
  # Docker refuses removal of in-use images; no forced removal or ancestor pruning.
  docker image rm --no-prune "$ref"
done
for id in "${protected_ids[@]}" "${used_images[@]}"; do
  docker image inspect --format '{{.Id}}' "$id" >/dev/null
done
printf '[cleanup] After\n'
df -h "$HOME"
docker system df
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/actuator/health
printf '\n[cleanup] Images removed from VPS only; registry digests above allow re-pull. Volumes, backups and code untouched.\n'
