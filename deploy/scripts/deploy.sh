#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$DEPLOY_ROOT/compose.production.yaml"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$HOME/.config/quanlythuvien/backend.env}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/quanlythuvien}"
STATE_DIR="$DEPLOY_ROOT/state"
CURRENT_IMAGE_FILE="$STATE_DIR/current-image"
CURRENT_COMMIT_FILE="$STATE_DIR/current-commit"
LOCK_FILE="$STATE_DIR/deploy.lock"
NEW_IMAGE="${1:-}"
DEPLOY_COMMIT="${DEPLOY_COMMIT:-manual}"

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ ! "$NEW_IMAGE" =~ ^ghcr\.io/phandinhphuc1234/se330-be@sha256:[0-9a-f]{64}$ ]]; then
  fail 'The deployment image must be an immutable ghcr.io/phandinhphuc1234/se330-be@sha256:... reference.'
fi

bash "$SCRIPT_DIR/preflight.sh"
mkdir -p "$BACKUP_DIR" "$STATE_DIR"
chmod 700 "$BACKUP_DIR" "$STATE_DIR"

exec 9>"$LOCK_FILE"
flock -n 9 || fail 'Another deployment is already running.'

export RUNTIME_ENV_FILE
export APP_IMAGE="$NEW_IMAGE"

compose() {
  docker compose --project-name quanlythuvien \
    --env-file "$RUNTIME_ENV_FILE" \
    -f "$COMPOSE_FILE" "$@"
}

wait_for_healthy_service() {
  local service_name="$1"
  local timeout_seconds="$2"
  local started_at container_id status
  started_at="$(date +%s)"

  while true; do
    container_id="$(compose ps -q "$service_name")"
    if [[ -n "$container_id" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
      if [[ "$status" == 'healthy' || "$status" == 'running' ]]; then
        return 0
      fi
      if [[ "$status" == 'unhealthy' || "$status" == 'exited' || "$status" == 'dead' ]]; then
        return 1
      fi
    fi

    if (( $(date +%s) - started_at >= timeout_seconds )); then
      return 1
    fi
    sleep 5
  done
}

database_scalar() {
  local sql="$1"
  # Variables in the single-quoted command intentionally expand inside the container.
  # shellcheck disable=SC2016
  compose exec -T postgres sh -ceu \
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' sh "$sql"
}

create_backup() {
  local timestamp temporary_file backup_file
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_file="$BACKUP_DIR/library-${timestamp}.dump"
  temporary_file="${backup_file}.tmp"

  log "Creating PostgreSQL backup at $backup_file"
  # shellcheck disable=SC2016
  compose exec -T postgres sh -ceu \
    'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' \
    > "$temporary_file"
  [[ -s "$temporary_file" ]] || fail 'PostgreSQL backup is empty.'
  mv "$temporary_file" "$backup_file"
  sha256sum "$backup_file" > "${backup_file}.sha256"
  printf '%s\n' "$backup_file"
}

rollback_application() {
  local previous_image="$1"
  if [[ ! "$previous_image" =~ ^ghcr\.io/phandinhphuc1234/se330-be@sha256:[0-9a-f]{64}$ ]]; then
    log 'No valid previous immutable image is available for automatic rollback.'
    return 1
  fi

  log "Rolling application back to $previous_image"
  export APP_IMAGE="$previous_image"
  compose pull library-service
  compose up -d --no-deps --force-recreate library-service
  wait_for_healthy_service library-service 240
}

log "Starting deployment for commit $DEPLOY_COMMIT"
compose up -d postgres redis
wait_for_healthy_service postgres 120 || fail 'PostgreSQL did not become healthy.'
wait_for_healthy_service redis 120 || fail 'Redis did not become healthy.'

flyway_table="$(database_scalar "SELECT COALESCE(to_regclass('public.flyway_schema_history')::text, '');")"
[[ "$flyway_table" == 'flyway_schema_history' ]] || fail 'Flyway history is missing. Restore the approved database snapshot before deployment.'

v22_success="$(database_scalar "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '22' AND success = TRUE;")"
[[ "$v22_success" == '1' ]] || fail 'Flyway V22 is not recorded as successful. Refusing to deploy against this database.'

failed_migrations="$(database_scalar 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE;')"
[[ "$failed_migrations" == '0' ]] || fail 'Flyway history contains a failed migration.'

create_backup

previous_image=''
if [[ -f "$CURRENT_IMAGE_FILE" ]]; then
  previous_image="$(<"$CURRENT_IMAGE_FILE")"
else
  existing_container="$(compose ps -q library-service)"
  if [[ -n "$existing_container" ]]; then
    previous_image="$(docker inspect --format '{{.Config.Image}}' "$existing_container")"
  fi
fi

log "Pulling $NEW_IMAGE"
compose pull library-service
log 'Replacing library-service.'
compose up -d --no-deps --force-recreate library-service

if ! wait_for_healthy_service library-service 300; then
  log 'New container failed its health check.'
  compose logs --no-color --tail 200 library-service >&2 || true
  if rollback_application "$previous_image"; then
    fail 'Deployment failed; the previous application image was restored. Database migrations were not reverted.'
  fi
  fail 'Deployment failed and no automatic application rollback was possible.'
fi

active_flyway_version="$(database_scalar 'SELECT COALESCE(MAX(installed_rank), 0) FROM flyway_schema_history WHERE success = TRUE;')"
printf '%s\n' "$NEW_IMAGE" > "${CURRENT_IMAGE_FILE}.tmp"
mv "${CURRENT_IMAGE_FILE}.tmp" "$CURRENT_IMAGE_FILE"
printf '%s\n' "$DEPLOY_COMMIT" > "${CURRENT_COMMIT_FILE}.tmp"
mv "${CURRENT_COMMIT_FILE}.tmp" "$CURRENT_COMMIT_FILE"

log "Deployment succeeded. Flyway installed rank: $active_flyway_version"
compose ps
