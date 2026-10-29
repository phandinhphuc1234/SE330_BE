#!/usr/bin/env bash
# Approved storage maintenance: consistent backup, same-length reclamation, S3 verification.
set -Eeuo pipefail
umask 077
fail() { printf '[seaweed-reclaim] ERROR: %s\n' "$*" >&2; exit 1; }
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ "${LIBRARY_MAINTENANCE_RUN:-}" =~ ^[0-9]+-[0-9]+$ ]] || fail 'Validated run identity required.'
state_dir="$HOME/apps/quanlythuvien/state"
exec 9>"$state_dir/deploy.lock"
flock -n 9 || fail 'Deployment or maintenance already active.'
[[ "$(<"$state_dir/current-image")" == ghcr.io/phandinhphuc1234/se330-be@sha256:c2a4630a553224b87402b4d5f2ac32c615f5dab503c6e7c19af683c958bd1562 ]] || fail 'Backend changed; re-review.'
[[ "$(<"$state_dir/current-rag-image")" == ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e ]] || fail 'RAG changed; re-review.'
sudo -n python3 -c 'import sys; assert sys.version_info >= (3, 11)'
one_container() {
  local result
  result=$(docker ps -q --filter label=com.docker.compose.project=quanlythuvien --filter "label=com.docker.compose.service=$1")
  [[ "$result" =~ ^[a-f0-9]{12}$ ]] || fail "One running $1 container required."
  printf '%s' "$result"
}
seaweed=$(one_container rag-seaweedfs)
worker=$(one_container rag-worker)
rag=$(one_container rag-api)
[[ "$(docker inspect --format '{{.Config.Image}}' "$seaweed")" == chrislusf/seaweedfs:3.85 ]] || fail 'SeaweedFS image changed.'
volume_root=$(docker volume inspect --format '{{.Mountpoint}}' quanlythuvien_rag_seaweedfs_data)
[[ "$volume_root" == /var/lib/docker/volumes/quanlythuvien_rag_seaweedfs_data/_data ]] || fail 'Unexpected volume root.'
# Only the reviewed storage container may mount the data volume (even stopped containers).
for container in $(docker ps -aq); do
  names=$(docker inspect --format '{{range .Mounts}}{{if eq .Type "volume"}}{{println .Name}}{{end}}{{end}}' "$container")
  if grep -Fxq quanlythuvien_rag_seaweedfs_data <<< "$names"; then
    full_id=$(docker inspect --format '{{.Id}}' "$container")
    [[ "$full_id" == "$seaweed"* ]] || fail 'Another container mounts storage.'
  fi
done
args=$(docker inspect --format '{{range .Config.Cmd}}{{println .}}{{end}}' "$seaweed")
if grep -Eiq 'volumePreallocate(=true)?$' <<< "$args"; then fail 'Disable live preallocation before maintenance.'; fi
backup=$(mktemp -d "$HOME/backups/quanlythuvien/seaweed-20261029-XXXXXX")
container_dir="/tmp/library-seaweed-${LIBRARY_MAINTENANCE_RUN}"
docker exec "$rag" mkdir -m 700 "$container_dir"
docker exec "$worker" python -c 'from celery import Celery; from app.core.config import get_settings; s=get_settings(); a=Celery("maintenance",broker=s.effective_celery_broker_url); i=a.control.inspect(timeout=5); replies=[i.active(),i.reserved(),i.scheduled()]; assert all(replies) and all(not tasks for reply in replies for tasks in reply.values()), "Worker must be idle"; print("[seaweed-reclaim] Worker idle checks passed")'
worker_stopped=false
storage_stopped=false
python_started=false
resume() {
  local result=$? verified=true
  trap - EXIT
  if [[ "$storage_stopped" == true && "$python_started" == true ]]; then
    verified=$(sudo -n python3 -c 'import json,pathlib,sys; p=pathlib.Path(sys.argv[1]); print(str(not p.exists() or json.loads(p.read_text()).get("dataVerified") is True).lower())' "$backup/recovery-state.json") || verified=false
  fi
  if [[ "$verified" == true ]]; then
    if [[ "$storage_stopped" == true ]]; then docker start "$seaweed" || result=1; fi
    if [[ "$worker_stopped" == true ]]; then docker start "$worker" || result=1; fi
  else
    printf '[seaweed-reclaim] Data verification failed: storage/worker stay stopped; retain backup for recovery.\n' >&2
  fi
  exit "$result"
}
trap resume EXIT
printf '[seaweed-reclaim] Private backup directory: %s\n' "$backup"
worker_stopped=true
docker stop --timeout 90 "$worker"
docker exec -i "$rag" python - --output "$container_dir/before.json" < "$script_dir/seaweed-object-snapshot.py"
docker cp "$rag:$container_dir/before.json" "$backup/s3-before.json"
printf '[seaweed-reclaim] Stopping the only storage writer.\n'
storage_stopped=true
docker stop --timeout 60 "$seaweed"
[[ "$(docker inspect --format '{{.State.Running}}' "$seaweed")" == false ]] || fail 'Storage writer still running.'
python_started=true
sudo -n python3 "$script_dir/reclaim-seaweed-preallocation.py" --root "$volume_root" --backup "$backup"
docker start "$seaweed"
storage_stopped=false
healthy=false
for attempt in $(seq 1 30); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' "$seaweed")" == healthy ]]; then healthy=true; break; fi
  sleep 4
done
[[ "$healthy" == true ]] || fail 'SeaweedFS did not become healthy.'
docker exec -i "$rag" python - --output "$container_dir/after.json" --compare "$container_dir/before.json" < "$script_dir/seaweed-object-snapshot.py"
docker cp "$rag:$container_dir/after.json" "$backup/s3-after.json"
docker start "$worker"
worker_stopped=false
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/actuator/health
printf '\n[seaweed-reclaim] Data/checksum/health checks passed. No file or volume deleted.\n'
df -h "$HOME"
available_kb=$(df -Pk "$HOME" | awk 'NR == 2 {print $4}')
(( available_kb >= 8388608 )) || fail 'Below the unchanged 8 GiB deployment guard.'
