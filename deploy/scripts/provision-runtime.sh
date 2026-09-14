#!/usr/bin/env bash
# Mục đích: tạo/cập nhật backend.env production trực tiếp trên VPS mà không đưa
# secret qua repository hoặc GitHub Actions log.

# Bước 1: bật chế độ Bash nghiêm ngặt. umask 077 làm file mới mặc định chỉ cho
# owner truy cập, tránh lộ secret trong khoảng thời gian file đang được tạo.
set -Eeuo pipefail
umask 077

# Bước 2: xác định deployment root, template và file runtime thật trên VPS.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$HOME/.config/quanlythuvien/backend.env}"
EXAMPLE_ENV_FILE="$DEPLOY_ROOT/runtime.env.example"

# Bước 3: chuẩn hóa log. Nội dung log chỉ chứa tên biến, không chứa giá trị.
log() {
  printf '[provision] %s\n' "$*"
}

fail() {
  printf '[provision] ERROR: %s\n' "$*" >&2
  exit 1
}

# Bước 4: giải mã URL public do workflow truyền bằng Base64. Base64 ở đây chỉ
# giúp truyền dữ liệu qua SSH an toàn về cú pháp, không phải cơ chế mã hóa secret.
decode_required() {
  local variable_name="$1"
  local encoded_value="${!variable_name:-}"
  [[ -n "$encoded_value" ]] || fail "$variable_name is required."
  printf '%s' "$encoded_value" | base64 --decode
}

# Bước 5: chỉ chấp nhận URL HTTP/HTTPS không có whitespace hoặc dấu nháy để
# ngăn cấu hình lỗi và tránh dữ liệu input phá vỡ định dạng backend.env.
validate_http_url() {
  local variable_name="$1"
  local value="$2"
  [[ "$value" == http://* || "$value" == https://* ]] || \
    fail "$variable_name must start with http:// or https://."
  [[ ! "$value" =~ [[:space:]] ]] || fail "$variable_name cannot contain whitespace."
  [[ "$value" != *"'"* && "$value" != *'"'* ]] || \
    fail "$variable_name cannot contain quote characters."
}

# Bước 6: kiểm tra công cụ và template cần thiết trước khi thay đổi file thật.
command -v base64 >/dev/null 2>&1 || fail 'base64 is required.'
command -v openssl >/dev/null 2>&1 || fail 'openssl is required to generate runtime secrets.'
[[ -f "$EXAMPLE_ENV_FILE" ]] || fail "Missing template: $EXAMPLE_ENV_FILE"

public_api_base_url="$(decode_required PUBLIC_API_BASE_URL_B64)"
frontend_origin="$(decode_required FRONTEND_ORIGIN_B64)"
public_api_base_url="${public_api_base_url%/}"
frontend_origin="${frontend_origin%/}"
validate_http_url PUBLIC_API_BASE_URL "$public_api_base_url"
validate_http_url FRONTEND_ORIGIN "$frontend_origin"

# Bước 7: bảo vệ thư mục runtime. Mode 700 = owner được truy cập đầy đủ, mọi
# user khác bị chặn. Symlink bị từ chối để tránh ghi nhầm sang file ngoài ý muốn.
runtime_dir="$(dirname -- "$RUNTIME_ENV_FILE")"
mkdir -p "$runtime_dir"
chmod 700 "$runtime_dir"
[[ ! -L "$RUNTIME_ENV_FILE" ]] || fail 'Refusing to replace a symbolic-link runtime environment.'

# Bước 8: làm việc trên file tạm cùng thư mục. Nếu script lỗi, trap xóa file tạm
# và backend.env cũ vẫn nguyên vẹn.
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

# Bước 9: lấy cấu hình hiện có để bảo toàn secret thật; chỉ dùng template khi VPS
# chưa từng được provision.
if [[ -f "$RUNTIME_ENV_FILE" ]]; then
  cp -- "$RUNTIME_ENV_FILE" "$working_file"
  log 'Updating the existing runtime environment without rotating valid secrets.'
else
  cp -- "$EXAMPLE_ENV_FILE" "$working_file"
  log 'Creating the runtime environment from the production template.'
fi

# Bước 10: các helper bên dưới đọc/cập nhật một biến trên bản tạm. set_value giữ
# đúng một dòng cho mỗi key và chưa chạm tới backend.env đang hoạt động.
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

# Bước 11: sinh secret bằng CSPRNG của OpenSSL khi giá trị trống/CHANGE_ME; secret
# hợp lệ được giữ nguyên để chạy lại workflow không làm PostgreSQL mất kết nối.
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

# Bước 12: thêm giá trị mặc định cho setting còn thiếu/placeholder nhưng không
# ghi đè cấu hình hợp lệ mà người vận hành đã chủ động thiết lập.
ensure_setting() {
  local key="$1"
  local default_value="$2"
  local current_value
  current_value="$(get_value "$key")"
  if [[ -z "$current_value" || "$current_value" == *CHANGE_ME* || "$current_value" == *example.com* ]]; then
    set_value "$key" "$default_value"
  fi
}

# Bước 13: provision tên DB/user ổn định và các secret cốt lõi của hệ thống.
ensure_setting LIBRARY_APP_PORT '8080'
ensure_setting POSTGRES_DB 'library'
ensure_setting POSTGRES_USER 'library_app'
ensure_secret POSTGRES_PASSWORD 32
ensure_secret REDIS_PASSWORD 32
ensure_secret JWT_SECRET 32
ensure_secret EBOOK_READING_SESSION_SECRET 32

# Bước 14: RAG, payment và object storage hiện chưa dùng nên mặc định tắt/local.
# Credential vẫn được sinh để không còn giá trị mẫu nếu một client bị bật nhầm.
ensure_secret OBJECT_STORAGE_ACCESS_KEY 16
ensure_secret OBJECT_STORAGE_SECRET_KEY 32
ensure_setting RAG_ENABLED 'false'
ensure_setting VNPAY_ENABLED 'false'
ensure_setting OBJECT_STORAGE_ENDPOINT 'http://127.0.0.1:8333'
ensure_setting OBJECT_STORAGE_PUBLIC_ENDPOINT 'http://127.0.0.1:8333'

# Bước 15: cập nhật URL/CORS theo input hiện tại. Đây là dữ liệu public, không
# phải secret, và được phép thay đổi khi chuyển từ IP HTTP sang HTTPS/domain.
set_value CORS_ALLOWED_ORIGINS "$frontend_origin"
set_value CORS_ALLOW_CREDENTIALS 'true'
set_value APP_PASSWORD_RESET_BASE_URL "${frontend_origin}/reset-password"
set_value VNPAY_RETURN_URL "${frontend_origin}/payment/vnpay-return"
set_value VNPAY_IPN_URL "${public_api_base_url}/api/payments/ipn/vnpay"

# Bước 16: cookie chỉ bật Secure/SameSite=None khi API đã thực sự dùng HTTPS.
# Giai đoạn HTTP tạm thời dùng Secure=false và SameSite=Lax.
if [[ "$public_api_base_url" == https://* ]]; then
  set_value REFRESH_TOKEN_SECURE 'true'
  set_value REFRESH_TOKEN_SAME_SITE 'None'
else
  set_value REFRESH_TOKEN_SECURE 'false'
  set_value REFRESH_TOKEN_SAME_SITE 'Lax'
fi

# Bước 17: email mẫu không được dùng để gửi thật; giữ địa chỉ .invalid và tắt
# mail health indicator cho đến khi RESEND_API_KEY/MAIL_FROM được cấu hình riêng.
mail_from="$(get_value MAIL_FROM)"
if [[ -z "$mail_from" || "$mail_from" == *example.com* ]]; then
  set_value MAIL_FROM 'no-reply@localhost.invalid'
fi
ensure_setting MANAGEMENT_HEALTH_MAIL_ENABLED 'false'

# Bước 18: kiểm tra lần cuối để không ghi file còn placeholder hoặc JWT quá ngắn.
if grep -Eq '^[A-Z][A-Z0-9_]*=.*CHANGE_ME' "$working_file"; then
  fail 'A CHANGE_ME placeholder remains in the generated runtime environment.'
fi

jwt_secret="$(get_value JWT_SECRET)"
(( ${#jwt_secret} >= 64 )) || fail 'Generated JWT_SECRET is unexpectedly short.'

# Bước 19: mode 600 = owner của file được đọc và sửa; group/other không có quyền.
# mv trong cùng thư mục thay file theo kiểu nguyên tử, tránh backend.env nửa chừng.
chmod 600 "$working_file"
mv -- "$working_file" "$RUNTIME_ENV_FILE"
working_file=''
log "Runtime environment provisioned at $RUNTIME_ENV_FILE."
log 'Secret values were not printed and existing valid secrets were not rotated.'

# FLOW TÓM TẮT:
# nhận URL public -> kiểm tra input -> đọc file cũ/template -> sinh secret còn
# thiếu -> cập nhật URL/cookie -> validate -> chmod 600 -> thay backend.env.
#
# VẤN ĐỀ GIẢI QUYẾT:
# loại bỏ việc nhập tay mật khẩu production, không đưa secret lên GitHub, không
# làm lộ secret trong log và không tự ý rotate mật khẩu khi workflow chạy lại.
