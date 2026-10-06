#!/usr/bin/env bash
# Muc dich: kiem tra production qua SSH ma khong sua config, restart hay deploy.
set -Eeuo pipefail

# Buoc 1: chi doc tai nguyen host va container cua dung project thu vien.
printf '[status] Host resources\n'
uptime
free -m
df -h "$HOME"
docker ps -a --filter label=com.docker.compose.project=quanlythuvien \
  --format '{{.Names}} | {{.Image}} | {{.Status}} | {{.Ports}}'

# Buoc 2: chi in allowlist config public. Khong in toan bo env/docker inspect.
runtime_file="${RUNTIME_ENV_FILE:-$HOME/.config/quanlythuvien/backend.env}"
if [[ -f "$runtime_file" ]]; then
  printf '[status] Public runtime configuration\n'
  grep -E '^(RAG_ENABLED|CHUNKING_STRATEGY_VERSION|EMBEDDING_PROVIDER|EMBEDDING_MODEL|EMBEDDING_DIM|EMBEDDING_VERSION|LLM_PROVIDER|LLM_MODEL|RETRIEVAL_MODE|CORS_ALLOWED_ORIGINS|LIBRARY_APP_PORT)=' "$runtime_file" || true
  # Chi bao co/khong, khong bao do dai hay gia tri secret.
  for key in GEMINI_API_KEY OPENAI_API_KEY RAG_INTERNAL_API_KEY; do
    if grep -Eq "^${key}=.+" "$runtime_file"; then
      printf '[status] %s: configured\n' "$key"
    else
      printf '[status] %s: missing\n' "$key"
    fi
  done
fi

# Buoc 3: doc commit state va health noi bo; khong goi embedding/LLM co tinh phi.
commit_file="$HOME/apps/quanlythuvien/state/current-commit"
if [[ -f "$commit_file" ]]; then
  printf '[status] Deployed commit: '
  head -n 1 "$commit_file"
fi
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/actuator/health
printf '\n'

# FLOW: doc RAM/disk -> container status -> public config + secret presence
# -> deployed commit -> Spring health. Giai quyet viec chan doan VPS an toan,
# khong can tai private key ve local va khong de secret xuat hien trong log.
