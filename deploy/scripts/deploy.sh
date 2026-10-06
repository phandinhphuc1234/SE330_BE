#!/usr/bin/env bash
# Mục đích: deploy đúng Docker image đã qua CI lên VPS, backup database trước khi
# thay application và tự phục hồi image cũ nếu healthcheck thất bại.

# Bước 1: bật chế độ Bash nghiêm ngặt và tạo file mới ở chế độ private mặc định.
set -Eeuo pipefail
umask 077

# Bước 2: xác định Compose/runtime/backup/state và nhận immutable image digest từ
# tham số thứ nhất của script.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$DEPLOY_ROOT/compose.production.yaml"
RAG_COMPOSE_FILE="$DEPLOY_ROOT/compose.rag.production.yaml"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$HOME/.config/quanlythuvien/backend.env}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/quanlythuvien}"
STATE_DIR="$DEPLOY_ROOT/state"
CURRENT_IMAGE_FILE="$STATE_DIR/current-image"
CURRENT_RAG_IMAGE_FILE="$STATE_DIR/current-rag-image"
CURRENT_COMMIT_FILE="$STATE_DIR/current-commit"
LOCK_FILE="$STATE_DIR/deploy.lock"
NEW_IMAGE="${1:-}"
NEW_RAG_IMAGE="${2:-}"
DEPLOY_COMMIT="${DEPLOY_COMMIT:-manual}"

# Bước 3: chuẩn hóa log thành công/lỗi cho GitHub Actions.
log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

read_setting() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" "$RUNTIME_ENV_FILE" | tail -n 1 || true)"
  printf '%s' "${line#*=}"
}

# Bước 4: chỉ nhận image của đúng repository và khóa bằng sha256 digest. Tag như
# latest không được chấp nhận vì có thể trỏ sang nội dung khác theo thời gian.
if [[ ! "$NEW_IMAGE" =~ ^ghcr\.io/phandinhphuc1234/se330-be@sha256:[0-9a-f]{64}$ ]]; then
  fail 'The deployment image must be an immutable ghcr.io/phandinhphuc1234/se330-be@sha256:... reference.'
fi

# Bước 5: chạy preflight cơ bản, tạo thư mục private và lấy exclusive lock. Lock
# ngăn hai workflow deploy/rollback cùng sửa production một lúc.
bash "$SCRIPT_DIR/preflight.sh"
mkdir -p "$BACKUP_DIR" "$STATE_DIR"
chmod 700 "$BACKUP_DIR" "$STATE_DIR"

exec 9>"$LOCK_FILE"
flock -n 9 || fail 'Another deployment is already running.'

export RUNTIME_ENV_FILE
export APP_IMAGE="$NEW_IMAGE"

rag_enabled="$(read_setting RAG_ENABLED)"
compose_files=(-f "$COMPOSE_FILE")
if [[ "$rag_enabled" == 'true' ]]; then
  compose_files+=(-f "$RAG_COMPOSE_FILE")
  if [[ -z "$NEW_RAG_IMAGE" && -f "$CURRENT_RAG_IMAGE_FILE" ]]; then
    NEW_RAG_IMAGE="$(<"$CURRENT_RAG_IMAGE_FILE")"
  fi
  if [[ ! "$NEW_RAG_IMAGE" =~ ^ghcr\.io/phandinhphuc1234/se330-rag@sha256:[0-9a-f]{64}$ ]]; then
    fail 'RAG is enabled, so the deployment requires an immutable ghcr.io/phandinhphuc1234/se330-rag@sha256:... reference.'
  fi
  export RAG_IMAGE="$NEW_RAG_IMAGE"
fi

# Bước 6: gom lệnh Compose với project name, runtime env và compose file cố định.
compose() {
  docker compose --project-name quanlythuvien \
    --env-file "$RUNTIME_ENV_FILE" \
    "${compose_files[@]}" "$@"
}

# Bước 7: chờ container đạt healthy/running trong thời hạn; unhealthy/exited/dead
# được xem là lỗi ngay để pipeline không chờ vô ích.
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

# Bước 8: chạy một truy vấn PostgreSQL scalar bên trong container mà không cần
# mở port 5432 ra Internet.
database_scalar() {
  local sql="$1"
  # Variables in the single-quoted command intentionally expand inside the container.
  # shellcheck disable=SC2016
  compose exec -T postgres sh -ceu \
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' sh "$sql"
}

# Bước 9: tạo custom-format pg_dump và checksum trong file tạm, chỉ đổi tên khi
# dump có dữ liệu. Backup xảy ra trước khi application mới được thay thế.
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

# RAG có schema Alembic riêng nên được backup bằng file riêng trước khi chạy
# migration Python. Hai database không chia sẻ lifecycle hoặc lịch sử migration.
create_rag_backup() {
  local timestamp temporary_file backup_file
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_file="$BACKUP_DIR/rag-${timestamp}.dump"
  temporary_file="${backup_file}.tmp"

  log "Creating RAG PostgreSQL backup at $backup_file"
  # shellcheck disable=SC2016
  compose exec -T rag-postgres sh -ceu \
    'exec pg_dump -U "$RAG_POSTGRES_USER" -d "$RAG_POSTGRES_DB" --format=custom --no-owner --no-privileges' \
    > "$temporary_file"
  [[ -s "$temporary_file" ]] || fail 'RAG PostgreSQL backup is empty.'
  mv "$temporary_file" "$backup_file"
  sha256sum "$backup_file" > "${backup_file}.sha256"
}

# Bước 10: nếu bản mới lỗi, thử kéo lại immutable image trước đó và chờ health.
# Chỉ application image được rollback; migration database không tự hạ version.
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

rollback_rag_application() {
  local previous_rag_image="$1"
  if [[ ! "$previous_rag_image" =~ ^ghcr\.io/phandinhphuc1234/se330-rag@sha256:[0-9a-f]{64}$ ]]; then
    log 'No valid previous immutable RAG image is available for automatic rollback.'
    return 1
  fi

  log "Rolling RAG application back to $previous_rag_image"
  export RAG_IMAGE="$previous_rag_image"
  compose pull rag-api rag-worker
  compose up -d --no-deps --force-recreate rag-api rag-worker
  wait_for_healthy_service rag-api 300 && wait_for_healthy_service rag-worker 120
}

# Bước 11: khởi động PostgreSQL/Redis trước và đợi cả hai sẵn sàng.
log "Starting deployment for commit $DEPLOY_COMMIT"
compose up -d postgres redis
wait_for_healthy_service postgres 120 || fail 'PostgreSQL did not become healthy.'
wait_for_healthy_service redis 120 || fail 'Redis did not become healthy.'

# Bước 12: chặn deploy nếu thiếu lịch sử Flyway, thiếu V22 hoặc từng có migration
# thất bại. Đây là hàng rào bảo vệ riêng cho snapshot lịch sử của dự án.
flyway_table="$(database_scalar "SELECT COALESCE(to_regclass('public.flyway_schema_history')::text, '');")"
[[ "$flyway_table" == 'flyway_schema_history' ]] || fail 'Flyway history is missing. Restore the approved database snapshot before deployment.'

v22_success="$(database_scalar "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '22' AND success = TRUE;")"
[[ "$v22_success" == '1' ]] || fail 'Flyway V22 is not recorded as successful. Refusing to deploy against this database.'

failed_migrations="$(database_scalar 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE;')"
[[ "$failed_migrations" == '0' ]] || fail 'Flyway history contains a failed migration.'

# Bước 13: backup database Spring hiện tại trước mọi thay đổi application.
create_backup

# Bước 14: ghi nhận các image đang chạy để có đích rollback nếu bản mới không
# healthy. State file được ưu tiên; Docker inspect là fallback cho lần đầu dùng
# pipeline có state tracking.
previous_image=''
if [[ -f "$CURRENT_IMAGE_FILE" ]]; then
  previous_image="$(<"$CURRENT_IMAGE_FILE")"
else
  existing_container="$(compose ps -q library-service)"
  if [[ -n "$existing_container" ]]; then
    previous_image="$(docker inspect --format '{{.Config.Image}}' "$existing_container")"
  fi
fi

previous_rag_image=''
if [[ "$rag_enabled" == 'true' ]]; then
  if [[ -f "$CURRENT_RAG_IMAGE_FILE" ]]; then
    previous_rag_image="$(<"$CURRENT_RAG_IMAGE_FILE")"
  else
    existing_rag_container="$(compose ps -q rag-api)"
    if [[ -n "$existing_rag_container" ]]; then
      previous_rag_image="$(docker inspect --format '{{.Config.Image}}' "$existing_rag_container")"
    fi
  fi
fi

# Bước 15: khi RAG bật, khởi động private dependencies trước, backup database
# RAG, tạo bucket idempotently, chạy Alembic một lần rồi mới thay API/worker.
if [[ "$rag_enabled" == 'true' ]]; then
  log 'Starting private RAG data services.'
  compose up -d rag-postgres rag-redis rag-qdrant rag-seaweedfs
  wait_for_healthy_service rag-postgres 180 || fail 'RAG PostgreSQL did not become healthy.'
  wait_for_healthy_service rag-redis 120 || fail 'RAG Redis did not become healthy.'
  wait_for_healthy_service rag-qdrant 180 || fail 'Qdrant did not become healthy.'
  wait_for_healthy_service rag-seaweedfs 180 || fail 'SeaweedFS did not become healthy.'

  create_rag_backup
  log "Pulling $NEW_RAG_IMAGE"
  compose pull rag-api rag-worker rag-migrate
  compose run --rm rag-seaweedfs-init
  compose run --rm rag-migrate
  compose up -d --no-deps --force-recreate rag-api rag-worker

  if ! wait_for_healthy_service rag-api 300 || ! wait_for_healthy_service rag-worker 120; then
    log 'New RAG application failed its health check.'
    compose logs --no-color --tail 200 rag-api rag-worker >&2 || true
    if rollback_rag_application "$previous_rag_image"; then
      fail 'RAG deployment failed; the previous RAG image was restored. Alembic migrations were not reverted.'
    fi
    compose stop rag-api rag-worker >/dev/null 2>&1 || true
    fail 'RAG deployment failed and no automatic RAG application rollback was possible.'
  fi
fi

# Bước 16: pull và chỉ recreate library-service; các database/cache tiếp tục chạy
# và giữ dữ liệu trong Docker volumes.
log "Pulling $NEW_IMAGE"
compose pull library-service
log 'Replacing library-service.'
compose up -d --no-deps --force-recreate library-service

# Bước 17: kiểm tra Spring application mới. Nếu lỗi, phục hồi Spring và RAG về
# image trước đó khi có thể; database migrations không tự hạ version.
if ! wait_for_healthy_service library-service 300; then
  log 'New container failed its health check.'
  compose logs --no-color --tail 200 library-service >&2 || true
  application_rollback_ok='false'
  rag_rollback_ok='true'
  if rollback_application "$previous_image"; then
    application_rollback_ok='true'
  fi
  if [[ "$rag_enabled" == 'true' && "$previous_rag_image" != "$NEW_RAG_IMAGE" ]]; then
    if ! rollback_rag_application "$previous_rag_image"; then
      rag_rollback_ok='false'
    fi
  fi
  if [[ "$application_rollback_ok" == 'true' && "$rag_rollback_ok" == 'true' ]]; then
    fail 'Deployment failed; previous application images were restored. Database migrations were not reverted.'
  fi
  fail 'Deployment failed and no automatic application rollback was possible.'
fi

# Bước 18: sau khi healthcheck đạt, ghi lại image/commit đang chạy bằng file tạm
# rồi đổi tên để state không bị ghi dở.
active_flyway_version="$(database_scalar 'SELECT COALESCE(MAX(installed_rank), 0) FROM flyway_schema_history WHERE success = TRUE;')"
printf '%s\n' "$NEW_IMAGE" > "${CURRENT_IMAGE_FILE}.tmp"
mv "${CURRENT_IMAGE_FILE}.tmp" "$CURRENT_IMAGE_FILE"
if [[ "$rag_enabled" == 'true' ]]; then
  printf '%s\n' "$NEW_RAG_IMAGE" > "${CURRENT_RAG_IMAGE_FILE}.tmp"
  mv "${CURRENT_RAG_IMAGE_FILE}.tmp" "$CURRENT_RAG_IMAGE_FILE"
fi
printf '%s\n' "$DEPLOY_COMMIT" > "${CURRENT_COMMIT_FILE}.tmp"
mv "${CURRENT_COMMIT_FILE}.tmp" "$CURRENT_COMMIT_FILE"

log "Deployment succeeded. Flyway installed rank: $active_flyway_version"
compose ps

# FLOW TÓM TẮT:
# validate immutable images -> preflight + lock -> start Spring DB/Redis
# -> kiểm tra Flyway + backup -> nếu bật RAG: start private data services,
# backup RAG DB, init bucket, chạy Alembic, deploy/healthcheck RAG
# -> deploy/healthcheck Spring -> rollback application images nếu lỗi
# -> lưu state nếu toàn bộ thành công.
#
# VẤN ĐỀ GIẢI QUYẾT:
# bảo đảm production chỉ chạy image bất biến đã qua CI, migration có backup,
# RAG không lộ port công khai, không deploy chồng chéo và có application rollback.
