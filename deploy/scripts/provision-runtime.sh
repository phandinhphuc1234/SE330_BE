#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$HOME/.config/quanlythuvien/backend.env}"
EXAMPLE_ENV_FILE="$DEPLOY_ROOT/runtime.env.example"

log() {
  printf '[provision] %s\n' "$*"
}

fail() {
  printf '[provision] ERROR: %s\n' "$*" >&2
  exit 1
}

decode_required() {
  local variable_name="$1"
  local encoded_value="${!variable_name:-}"
  [[ -n "$encoded_value" ]] || fail "$variable_name is required."
  printf '%s' "$encoded_value" | base64 --decode
}

validate_http_url() {
  local variable_name="$1"
  local value="$2"
  [[ "$value" == http://* || "$value" == https://* ]] || \
    fail "$variable_name must start with http:// or https://."
  [[ ! "$value" =~ [[:space:]] ]] || fail "$variable_name cannot contain whitespace."
  [[ "$value" != *"'"* && "$value" != *'"'* ]] || \
    fail "$variable_name cannot contain quote characters."
}

command -v base64 >/dev/null 2>&1 || fail 'base64 is required.'
command -v openssl >/dev/null 2>&1 || fail 'openssl is required to generate runtime secrets.'
[[ -f "$EXAMPLE_ENV_FILE" ]] || fail "Missing template: $EXAMPLE_ENV_FILE"

public_api_base_url="$(decode_required PUBLIC_API_BASE_URL_B64)"
frontend_origin="$(decode_required FRONTEND_ORIGIN_B64)"
public_api_base_url="${public_api_base_url%/}"
frontend_origin="${frontend_origin%/}"
validate_http_url PUBLIC_API_BASE_URL "$public_api_base_url"
validate_http_url FRONTEND_ORIGIN "$frontend_origin"

runtime_dir="$(dirname -- "$RUNTIME_ENV_FILE")"
mkdir -p "$runtime_dir"
chmod 700 "$runtime_dir"
[[ ! -L "$RUNTIME_ENV_FILE" ]] || fail 'Refusing to replace a symbolic-link runtime environment.'

working_file="$(mktemp "$runtime_dir/backend.env.provision.XXXXXX")"
next_file=''
cleanup() {
  if [[ -n "$working_file" ]]; then
    rm -f -- "$working_file"
  fi
  if [[ -n "$next_file" ]]; then
    rm -f -- "$next_file"
  fi
}
trap cleanup EXIT

if [[ -f "$RUNTIME_ENV_FILE" ]]; then
  cp -- "$RUNTIME_ENV_FILE" "$working_file"
  log 'Updating the existing runtime environment without rotating valid secrets.'
else
  cp -- "$EXAMPLE_ENV_FILE" "$working_file"
  log 'Creating the runtime environment from the production template.'
fi

get_value() {
  local key="$1"
  awk -v key="$key" '
    index($0, key "=") == 1 {
      value = substr($0, length(key) + 2)
    }
    END { print value }
  ' "$working_file"
}

set_value() {
  local key="$1"
  local value="$2"
  next_file="$(mktemp "$runtime_dir/backend.env.provision.XXXXXX")"
  awk -v key="$key" -v value="$value" '
    BEGIN { found = 0 }
    index($0, key "=") == 1 {
      if (!found) {
        print key "=" value
        found = 1
      }
      next
    }
    { print }
    END {
      if (!found) {
        print key "=" value
      }
    }
  ' "$working_file" > "$next_file"
  mv -- "$next_file" "$working_file"
  next_file=''
}

ensure_secret() {
  local key="$1"
  local byte_count="$2"
  local current_value
  current_value="$(get_value "$key")"
  if [[ -z "$current_value" || "$current_value" == *CHANGE_ME* ]]; then
    set_value "$key" "$(openssl rand -hex "$byte_count")"
    log "Generated $key."
  else
    log "Preserved existing $key."
  fi
}

ensure_setting() {
  local key="$1"
  local default_value="$2"
  local current_value
  current_value="$(get_value "$key")"
  if [[ -z "$current_value" || "$current_value" == *CHANGE_ME* || "$current_value" == *example.com* ]]; then
    set_value "$key" "$default_value"
  fi
}

# Stable database/application identifiers. Only secrets are generated.
ensure_setting LIBRARY_APP_PORT '8080'
ensure_setting POSTGRES_DB 'library'
ensure_setting POSTGRES_USER 'library_app'
ensure_secret POSTGRES_PASSWORD 32
ensure_secret REDIS_PASSWORD 32
ensure_secret JWT_SECRET 32
ensure_secret EBOOK_READING_SESSION_SECRET 32

# Current deployment intentionally excludes RAG, payment, and object-storage use.
# Credentials are still non-placeholder so accidentally enabling a client never
# falls back to the public template values.
ensure_secret OBJECT_STORAGE_ACCESS_KEY 16
ensure_secret OBJECT_STORAGE_SECRET_KEY 32
ensure_setting RAG_ENABLED 'false'
ensure_setting VNPAY_ENABLED 'false'
ensure_setting OBJECT_STORAGE_ENDPOINT 'http://127.0.0.1:8333'
ensure_setting OBJECT_STORAGE_PUBLIC_ENDPOINT 'http://127.0.0.1:8333'

set_value CORS_ALLOWED_ORIGINS "$frontend_origin"
set_value CORS_ALLOW_CREDENTIALS 'true'
set_value APP_VERIFICATION_BASE_URL "$public_api_base_url"
set_value APP_PASSWORD_RESET_BASE_URL "${frontend_origin}/reset-password"
set_value VNPAY_RETURN_URL "${frontend_origin}/payment/vnpay-return"
set_value VNPAY_IPN_URL "${public_api_base_url}/api/payments/ipn/vnpay"

if [[ "$public_api_base_url" == https://* ]]; then
  set_value REFRESH_TOKEN_SECURE 'true'
  set_value REFRESH_TOKEN_SAME_SITE 'None'
else
  set_value REFRESH_TOKEN_SECURE 'false'
  set_value REFRESH_TOKEN_SAME_SITE 'Lax'
fi

mail_from="$(get_value MAIL_FROM)"
if [[ -z "$mail_from" || "$mail_from" == *example.com* ]]; then
  set_value MAIL_FROM 'no-reply@localhost.invalid'
fi

if grep -Eq '^[A-Z][A-Z0-9_]*=.*CHANGE_ME' "$working_file"; then
  fail 'A CHANGE_ME placeholder remains in the generated runtime environment.'
fi

jwt_secret="$(get_value JWT_SECRET)"
(( ${#jwt_secret} >= 64 )) || fail 'Generated JWT_SECRET is unexpectedly short.'

chmod 600 "$working_file"
mv -- "$working_file" "$RUNTIME_ENV_FILE"
working_file=''
log "Runtime environment provisioned at $RUNTIME_ENV_FILE."
log 'Secret values were not printed and existing valid secrets were not rotated.'
