#!/usr/bin/env bash
# Mục đích: cấp short-lived Let's Encrypt certificate cho IPv4 public, chuyển
# Nginx từ HTTP sang HTTPS và xác nhận cơ chế tự động gia hạn hoạt động.

# Bước 1: bật Bash strict mode và tạo file tạm ở chế độ private mặc định.
set -Eeuo pipefail
umask 077

# Bước 2: chuẩn hóa log và lỗi để GitHub Actions hiển thị dễ đọc.
log() {
  printf '[https] %s\n' "$*"
}

fail() {
  printf '[https] ERROR: %s\n' "$*" >&2
  exit 1
}

# Bước 3: xác định các deployment asset đã được workflow đồng bộ lên VPS.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SITE_TEMPLATE="$DEPLOY_ROOT/nginx/quanlythuvien-ip-https.conf.template"
RENEWAL_HOOK="$DEPLOY_ROOT/certbot/reload-nginx.sh"
SITE_FILE='/etc/nginx/sites-available/quanlythuvien'

# Bước 4: nhận IPv4/email từ GitHub Secrets và validate trước khi gọi Certbot.
PUBLIC_HOST="${PUBLIC_HOST:-}"
LETSENCRYPT_EMAIL="${LETSENCRYPT_EMAIL:-}"

[[ "$PUBLIC_HOST" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || \
  fail 'PUBLIC_HOST must be a public IPv4 address.'
IFS='.' read -r -a public_host_octets <<< "$PUBLIC_HOST"
for octet in "${public_host_octets[@]}"; do
  (( 10#$octet <= 255 )) || fail 'PUBLIC_HOST contains an invalid IPv4 octet.'
done

[[ "$LETSENCRYPT_EMAIL" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || \
  fail 'LETSENCRYPT_EMAIL is missing or invalid.'
[[ -r "$SITE_TEMPLATE" ]] || fail "Missing HTTPS template: $SITE_TEMPLATE"
[[ -r "$RENEWAL_HOOK" ]] || fail "Missing Certbot renewal hook: $RENEWAL_HOOK"

# Bước 5: chọn cách chạy lệnh quản trị. Workflow cần passwordless sudo vì Certbot,
# certificate private key và Nginx config đều thuộc root.
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

# Bước 6: chỉ tiếp tục khi Nginx HTTP và backend hiện tại đều khỏe. Port 80 là
# kênh Let's Encrypt dùng để kiểm tra quyền kiểm soát IP khi cấp/gia hạn cert.
command -v nginx >/dev/null 2>&1 || fail 'Nginx must be provisioned before HTTPS.'
command -v curl >/dev/null 2>&1 || fail 'curl is required.'
curl --fail --silent --show-error --max-time 10 --retry 3 \
  --retry-all-errors --retry-delay 1 \
  -H "Host: $PUBLIC_HOST" http://127.0.0.1/healthz >/dev/null || \
  fail 'The current Nginx HTTP health endpoint is not ready.'

# Bước 7: cài Certbot qua snap theo hướng dẫn chính thức. IP webroot support yêu
# cầu Certbot 5.4 trở lên; package khác trùng command bị chặn để tránh dùng nhầm.
if ! command -v snap >/dev/null 2>&1; then
  log 'Installing snapd for the official Certbot package.'
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get update
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y snapd
fi
root_cmd systemctl enable --now snapd.socket

if root_cmd snap list certbot >/dev/null 2>&1; then
  log 'Refreshing the installed Certbot snap.'
  root_cmd snap refresh certbot
else
  log 'Installing Certbot from the official snap channel.'
  root_cmd snap install --classic certbot
fi

if [[ -e /usr/local/bin/certbot && ! -L /usr/local/bin/certbot ]]; then
  fail '/usr/local/bin/certbot exists and is not a symlink; review it manually.'
fi
root_cmd ln -sfn /snap/bin/certbot /usr/local/bin/certbot

certbot_version="$(/usr/local/bin/certbot --version | awk '{print $2}')"
[[ -n "$certbot_version" ]] || fail 'Cannot determine the Certbot version.'
minimum_version='5.4'
if [[ "$(printf '%s\n' "$minimum_version" "$certbot_version" | sort -V | head -n 1)" != "$minimum_version" ]]; then
  fail "Certbot $minimum_version or newer is required; found $certbot_version."
fi
log "Certbot $certbot_version supports IP certificates with webroot validation."

# Bước 8: cài renewal deploy hook trước khi xin cert. Hook kiểm tra config rồi
# graceful reload Nginx sau mỗi lần Certbot thay certificate trên đĩa.
root_cmd install -m 0755 -d /etc/letsencrypt/renewal-hooks/deploy /var/www/certbot
root_cmd install -m 0755 "$RENEWAL_HOOK" \
  /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

# Bước 9: đồng ý Let's Encrypt Subscriber Agreement và xin short-lived profile
# bắt buộc cho IP certificate. Email/secret không được in bởi script.
log 'Requesting or reusing the short-lived IP certificate.'
root_cmd /usr/local/bin/certbot certonly \
  --non-interactive \
  --agree-tos \
  --email "$LETSENCRYPT_EMAIL" \
  --preferred-profile shortlived \
  --webroot \
  --webroot-path /var/www/certbot \
  --ip-address "$PUBLIC_HOST" \
  --cert-name "$PUBLIC_HOST" \
  --keep-until-expiring

certificate_dir="/etc/letsencrypt/live/$PUBLIC_HOST"
root_cmd test -r "$certificate_dir/fullchain.pem" || fail 'Certificate chain was not created.'
root_cmd test -r "$certificate_dir/privkey.pem" || fail 'Certificate private key was not created.'

# Bước 10: render HTTPS site vào file tạm, backup HTTP site hiện tại và chỉ giữ
# config mới khi nginx -t thành công. Nếu test lỗi, HTTP config cũ được phục hồi.
rendered_site="$(mktemp)"
backup_site="$(mktemp)"
had_previous_site='false'
cleanup() {
  rm -f "$rendered_site" "$backup_site"
}
trap cleanup EXIT

sed "s/__PUBLIC_HOST__/$PUBLIC_HOST/g" "$SITE_TEMPLATE" > "$rendered_site"
if [[ -r "$SITE_FILE" ]]; then
  cp "$SITE_FILE" "$backup_site"
  had_previous_site='true'
fi

root_cmd install -m 0644 "$rendered_site" "$SITE_FILE"
if ! root_cmd nginx -t; then
  if [[ "$had_previous_site" == 'true' ]]; then
    root_cmd install -m 0644 "$backup_site" "$SITE_FILE"
    root_cmd nginx -t
  else
    root_cmd rm -f "$SITE_FILE"
  fi
  fail 'HTTPS Nginx configuration is invalid; the previous HTTP site was restored.'
fi

# Bước 11: graceful reload và kiểm tra certificate hợp lệ ngay trên loopback. URL
# vẫn dùng IP public nên curl đồng thời kiểm tra đúng Subject Alternative Name.
root_cmd systemctl reload nginx
curl --fail --silent --show-error --max-time 15 --retry 6 \
  --retry-all-errors --retry-delay 1 \
  --connect-to "$PUBLIC_HOST:443:127.0.0.1:443" \
  "https://$PUBLIC_HOST/healthz" >/dev/null || \
  fail 'Local HTTPS health validation failed.'

# Bước 12: bật timer gia hạn của Certbot snap và dry-run đúng lineage. IP cert chỉ
# sống khoảng 6 ngày nên tự động gia hạn là điều kiện bắt buộc, không phải tùy chọn.
root_cmd systemctl enable --now snap.certbot.renew.timer
root_cmd /usr/local/bin/certbot renew --dry-run --run-deploy-hooks \
  --cert-name "$PUBLIC_HOST"

command -v openssl >/dev/null 2>&1 || fail 'openssl is required to inspect certificate expiry.'
certificate_expiry="$(root_cmd openssl x509 -enddate -noout -in "$certificate_dir/cert.pem")"
log "HTTPS is ready; $certificate_expiry. Automatic renewal dry-run passed."

# FLOW TÓM TẮT:
# validate IP/email -> kiểm tra HTTP/backend -> cài Certbot 5.4+ -> cài reload hook
# -> xin short-lived IP certificate -> render/test HTTPS config -> reload Nginx
# -> verify TLS/SAN -> bật timer -> chạy renewal dry-run.
#
# VẤN ĐỀ GIẢI QUYẾT:
# mã hóa JWT, mật khẩu và dữ liệu API khi chưa có domain; đồng thời tránh website
# hết HTTPS sau 6 ngày bằng renewal timer và deploy hook tự reload Nginx.
