#!/usr/bin/env bash
# Mục đích: cài và cấu hình Nginx làm public HTTP entry point cho backend khi
# dự án chưa có domain. Script không thay đổi container hoặc dữ liệu PostgreSQL.

# Bước 1: dừng ngay khi lệnh lỗi, biến chưa khai báo hoặc pipeline thất bại.
set -Eeuo pipefail

# Bước 2: chuẩn hóa log và lỗi để GitHub Actions hiển thị dễ đọc.
log() {
  printf '[nginx] %s\n' "$*"
}

fail() {
  printf '[nginx] ERROR: %s\n' "$*" >&2
  exit 1
}

# Bước 3: xác định thư mục deploy đã được workflow đồng bộ lên VPS.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SITE_TEMPLATE="$DEPLOY_ROOT/nginx/quanlythuvien-http.conf.template"
PROXY_TEMPLATE="$DEPLOY_ROOT/nginx/quanlythuvien-proxy.conf.example"

# Bước 4: nhận IPv4 public qua biến môi trường và kiểm tra từng octet. Việc giới
# hạn đầu vào giúp giá trị này có thể được thay vào Nginx config một cách an toàn.
PUBLIC_HOST="${PUBLIC_HOST:-}"
[[ "$PUBLIC_HOST" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || \
  fail 'PUBLIC_HOST must be a public IPv4 address.'

IFS='.' read -r -a public_host_octets <<< "$PUBLIC_HOST"
for octet in "${public_host_octets[@]}"; do
  (( 10#$octet <= 255 )) || fail 'PUBLIC_HOST contains an invalid IPv4 octet.'
done

[[ -r "$SITE_TEMPLATE" ]] || fail "Missing Nginx site template: $SITE_TEMPLATE"
[[ -r "$PROXY_TEMPLATE" ]] || fail "Missing Nginx proxy template: $PROXY_TEMPLATE"

# Bước 5: chọn cách chạy lệnh quản trị. Deploy user phải có passwordless sudo để
# workflow không dừng lại chờ nhập mật khẩu.
if [[ "${EUID}" -eq 0 ]]; then
  root_cmd() {
    "$@"
  }
else
  command -v sudo >/dev/null 2>&1 || fail 'sudo is required for a non-root deploy user.'
  sudo -n true >/dev/null 2>&1 || fail 'The deploy user needs passwordless sudo.'
  root_cmd() {
    sudo -n "$@"
  }
fi

# Bước 6: chỉ hỗ trợ Ubuntu/Debian và cài Nginx từ package repository của hệ điều
# hành nếu máy chủ chưa có.
[[ -r /etc/os-release ]] || fail 'Cannot identify the operating system.'
# shellcheck disable=SC1091
source /etc/os-release
case "${ID:-}" in
  ubuntu|debian) ;;
  *) fail "Only Ubuntu and Debian are supported automatically (found ${ID:-unknown})." ;;
esac

if ! command -v nginx >/dev/null 2>&1; then
  log 'Installing Nginx.'
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get update
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
else
  log 'Nginx is already installed; keeping the installed version.'
fi

# Bước 7: xác nhận backend private đang trả health trước khi mở reverse proxy ra
# Internet. Nếu backend lỗi, Nginx không được đổi cấu hình.
command -v curl >/dev/null 2>&1 || fail 'curl is required.'
curl --fail --silent --show-error --max-time 10 \
  http://127.0.0.1:8080/actuator/health >/dev/null || \
  fail 'The private backend health endpoint is not ready on 127.0.0.1:8080.'

# Bước 8: tạo webroot cho ACME, cài proxy snippet và render site config vào file
# tạm. File tạm được kiểm tra trước khi thay config đang hoạt động.
root_cmd install -m 0755 -d /etc/nginx/snippets /etc/nginx/sites-available \
  /etc/nginx/sites-enabled /var/www/certbot
root_cmd install -m 0644 "$PROXY_TEMPLATE" \
  /etc/nginx/snippets/quanlythuvien-proxy.conf

rendered_site="$(mktemp)"
trap 'rm -f "$rendered_site"' EXIT
sed "s/__PUBLIC_HOST__/$PUBLIC_HOST/g" "$SITE_TEMPLATE" > "$rendered_site"
root_cmd install -m 0644 "$rendered_site" \
  /etc/nginx/sites-available/quanlythuvien
root_cmd ln -sfn /etc/nginx/sites-available/quanlythuvien \
  /etc/nginx/sites-enabled/quanlythuvien

# Bước 9: bỏ site mặc định sau khi site của ứng dụng đã tồn tại. Đây là đường dẫn
# cụ thể của package Nginx, không xóa toàn bộ thư mục sites-enabled.
if [[ -e /etc/nginx/sites-enabled/default || -L /etc/nginx/sites-enabled/default ]]; then
  root_cmd rm -f /etc/nginx/sites-enabled/default
fi

# Bước 10: chỉ reload sau khi Nginx xác nhận toàn bộ config hợp lệ. Service được
# bật cùng hệ điều hành để proxy tự trở lại sau khi VPS reboot.
root_cmd nginx -t
root_cmd systemctl enable --now nginx
root_cmd systemctl reload nginx

# Bước 11: nếu UFW đang active thì chỉ mở HTTP profile. Firewall của nhà cung cấp
# cloud vẫn phải cho phép inbound TCP/80 ở security group tương ứng.
if command -v ufw >/dev/null 2>&1 && root_cmd ufw status | grep -q '^Status: active'; then
  root_cmd ufw allow 'Nginx HTTP'
fi

# Bước 12: kiểm tra đúng public entry point ngay trên VPS trước khi báo thành công.
# Retry ngắn cho phép worker cũ hoàn tất trong lúc Nginx đang graceful reload.
curl --fail --silent --show-error --max-time 10 --retry 6 \
  --retry-all-errors --retry-delay 1 \
  -H "Host: $PUBLIC_HOST" http://127.0.0.1/healthz >/dev/null || \
  fail 'Nginx health proxy did not return success.'

log "Nginx HTTP entry point is ready for $PUBLIC_HOST."

# FLOW TÓM TẮT:
# validate IPv4 -> kiểm tra quyền sudo/hệ điều hành -> cài Nginx nếu thiếu
# -> kiểm tra backend private -> render config -> nginx -t -> reload
# -> mở UFW nếu đang active -> gọi /healthz qua Nginx.
#
# VẤN ĐỀ GIẢI QUYẾT:
# đưa backend đang khóa ở 127.0.0.1:8080 ra Internet qua cổng 80, giữ database và
# Redis private, chặn Swagger/Actuator nhạy cảm và chuẩn bị sẵn webroot cho HTTPS.
