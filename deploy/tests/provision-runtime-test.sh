#!/usr/bin/env bash
# Test provisioning voi key gia va thu muc tam; khong cham runtime production.
set -Eeuo pipefail
test_dir="$(mktemp -d)"
test_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
trap 'rm -f -- "$test_dir/backend.env" "$test_dir/run.log"; rmdir -- "$test_dir"' EXIT
export RUNTIME_ENV_FILE="$test_dir/backend.env"
export PUBLIC_API_BASE_URL_B64 FRONTEND_ORIGIN_B64 SWAGGER_ENABLED_B64
PUBLIC_API_BASE_URL_B64="$(printf '%s' 'https://api.library.test' | base64 | tr -d '\n')"
FRONTEND_ORIGIN_B64="$(printf '%s' 'https://library.test' | base64 | tr -d '\n')"
SWAGGER_ENABLED_B64="$(printf '%s' 'false' | base64 | tr -d '\n')"

# Missing provider key must fail atomically: no runtime file is installed.
export RAG_ENABLED_INPUT_B64
RAG_ENABLED_INPUT_B64="$(printf '%s' 'true' | base64 | tr -d '\n')"
if bash "$test_root/scripts/provision-runtime.sh" < /dev/null > "$test_dir/run.log" 2>&1; then
  printf 'Expected missing-provider-key failure\n' >&2
  exit 1
fi
test ! -f "$RUNTIME_ENV_FILE"
grep -q 'Configure GEMINI_API_KEY' "$test_dir/run.log"

# A user-owned GitHub secret can enable v1 through stdin without log exposure.
fake_key='TEST_GEMINI_KEY_1234567890'
printf '%s' "$fake_key" | bash "$test_root/scripts/provision-runtime.sh" > "$test_dir/run.log"
grep -q '^RAG_ENABLED=true$' "$RUNTIME_ENV_FILE"
grep -q '^CHUNKING_STRATEGY_VERSION=v1$' "$RUNTIME_ENV_FILE"
grep -q '^LLM_PROVIDER=gemini$' "$RUNTIME_ENV_FILE"
grep -q '^LLM_MODEL=gemini-2.5-flash$' "$RUNTIME_ENV_FILE"
grep -q "^GEMINI_API_KEY=${fake_key}$" "$RUNTIME_ENV_FILE"
if grep -q "$fake_key" "$test_dir/run.log"; then
  printf 'Provider key leaked to provisioning log\n' >&2
  exit 1
fi
first_database_secret="$(grep '^POSTGRES_PASSWORD=' "$RUNTIME_ENV_FILE")"

# Re-running never rotates valid database/provider credentials.
printf '%s' 'TEST_ANOTHER_KEY_9876543210' | bash "$test_root/scripts/provision-runtime.sh" > "$test_dir/run.log"
grep -q "^GEMINI_API_KEY=${fake_key}$" "$RUNTIME_ENV_FILE"
test "$(grep '^POSTGRES_PASSWORD=' "$RUNTIME_ENV_FILE")" = "$first_database_secret"

# Default/unchanged keeps an already enabled RAG and requires no stdin key.
unset RAG_ENABLED_INPUT_B64
bash "$test_root/scripts/provision-runtime.sh" < /dev/null > "$test_dir/run.log"
grep -q '^RAG_ENABLED=true$' "$RUNTIME_ENV_FILE"
printf 'Provisioning tests passed: atomic failure, v1, secret redaction, idempotency.\n'

# FLOW: empty runtime/key -> safe failure -> fake key via stdin -> v1 runtime
# -> repeat + preserve credentials -> unchanged preserves RAG. No provider call.
