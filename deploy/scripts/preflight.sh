#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$HOME/.config/quanlythuvien/backend.env}"
EXAMPLE_ENV_FILE="$DEPLOY_ROOT/runtime.env.example"
CHECK_DATABASE="${1:-}"

log() {
  printf '[preflight] %s\n' "$*"
}

fail() {
  printf '[preflight] ERROR: %s\n' "$*" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail 'Docker Engine is not installed or is not on PATH.'
docker version >/dev/null 2>&1 || fail 'The deploy user cannot access the Docker daemon.'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required.'
command -v flock >/dev/null 2>&1 || fail 'flock is required (install util-linux).'
command -v sha256sum >/dev/null 2>&1 || fail 'sha256sum is required.'

mkdir -p "$(dirname -- "$RUNTIME_ENV_FILE")" "$HOME/backups/quanlythuvien" "$DEPLOY_ROOT/state"
chmod 700 "$(dirname -- "$RUNTIME_ENV_FILE")" "$HOME/backups/quanlythuvien" "$DEPLOY_ROOT/state"

if [[ ! -f "$RUNTIME_ENV_FILE" ]]; then
  install -m 600 "$EXAMPLE_ENV_FILE" "$RUNTIME_ENV_FILE"
  fail "Created $RUNTIME_ENV_FILE from the template. Replace CHANGE_ME values, then run preflight again."
fi

chmod 600 "$RUNTIME_ENV_FILE"

required_variables=(
  POSTGRES_DB
  POSTGRES_USER
  POSTGRES_PASSWORD
  REDIS_PASSWORD
  JWT_SECRET
  CORS_ALLOWED_ORIGINS
  APP_VERIFICATION_BASE_URL
  APP_PASSWORD_RESET_BASE_URL
  REFRESH_TOKEN_SECURE
  VNPAY_ENABLED
  RAG_ENABLED
)

for variable_name in "${required_variables[@]}"; do
  line="$(grep -E "^${variable_name}=" "$RUNTIME_ENV_FILE" | tail -n 1 || true)"
  value="${line#*=}"
  if [[ -z "$line" || -z "$value" ]]; then
    fail "$variable_name is missing or empty in $RUNTIME_ENV_FILE."
  fi
  if [[ "$value" == *CHANGE_ME* || "$value" == *example.com* ]]; then
    fail "$variable_name still contains a template value."
  fi
done

jwt_line="$(grep -E '^JWT_SECRET=' "$RUNTIME_ENV_FILE" | tail -n 1)"
jwt_value="${jwt_line#*=}"
if (( ${#jwt_value} < 64 )); then
  fail 'JWT_SECRET must contain at least 64 characters.'
fi

available_kb="$(df -Pk "$HOME" | awk 'NR == 2 {print $4}')"
if [[ -z "$available_kb" || "$available_kb" -lt 2097152 ]]; then
  fail 'At least 2 GiB of free disk space is required before deployment.'
fi

export RUNTIME_ENV_FILE
APP_IMAGE='ghcr.io/phandinhphuc1234/se330-be:preflight' \
  docker compose --env-file "$RUNTIME_ENV_FILE" -f "$DEPLOY_ROOT/compose.production.yaml" config --quiet

if [[ "$CHECK_DATABASE" == '--database' ]]; then
  export RUNTIME_ENV_FILE
  export APP_IMAGE='ghcr.io/phandinhphuc1234/se330-be:preflight'
  compose=(
    docker compose
    --project-name quanlythuvien
    --env-file "$RUNTIME_ENV_FILE"
    -f "$DEPLOY_ROOT/compose.production.yaml"
  )
  "${compose[@]}" up -d --wait --wait-timeout 120 postgres redis

  # Variables in the single-quoted command intentionally expand inside the container.
  # shellcheck disable=SC2016
  flyway_table="$("${compose[@]}" exec -T postgres sh -ceu \
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT COALESCE(to_regclass('"'"'public.flyway_schema_history'"'"')::text, '"'"''"'"');"')"
  [[ "$flyway_table" == 'flyway_schema_history' ]] || \
    fail 'Database has no Flyway history. Restore the approved V22-compatible snapshot.'

  # shellcheck disable=SC2016
  v22_success="$("${compose[@]}" exec -T postgres sh -ceu \
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '"'"'22'"'"' AND success = TRUE;"')"
  [[ "$v22_success" == '1' ]] || fail 'Database does not contain a successful Flyway V22 migration.'
  log 'Database snapshot and Flyway V22 check passed.'
fi

log 'SSH session is working.'
log 'Docker Engine and Compose are available to the deploy user.'
log 'Runtime environment file exists, is non-placeholder, and Compose is valid.'
log 'Disk-space check passed.'
log 'VPS preflight passed.'
