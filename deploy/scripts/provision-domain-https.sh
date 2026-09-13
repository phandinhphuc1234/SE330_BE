#!/usr/bin/env bash
# Mục đích: cấp chứng chỉ Let's Encrypt cho API domain, chuyển HTTP sang domain
# HTTPS và giữ HTTPS theo IP trong thời gian frontend chuyển đổi endpoint.

# Bước 1: bật Bash strict mode và đặt quyền private mặc định cho file tạm.
set -Eeuo pipefail
umask 077

# Bước 2: chuẩn hóa log và lỗi để GitHub Actions hiển thị dễ đọc.
log() {
  printf '[domain-https] %s\n' "$*"
}

fail() {
  printf '[domain-https] ERROR: %s\n' "$*" >&2
  exit 1
}

# Bước 3: xác định các deployment asset đã được workflow đồng bộ lên VPS.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SITE_TEMPLATE="$DEPLOY_ROOT/nginx/quanlythuvien-domain-https.conf.template"
RENEWAL_HOOK="$DEPLOY_ROOT/certbot/reload-nginx.sh"
SITE_FILE='/etc/nginx/sites-available/quanlythuvien'

# Bước 4: nhận domain, IPv4 và email qua biến môi trường rồi validate trước khi
# dùng trong DNS check, Certbot hoặc Nginx template.
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-}"
PUBLIC_IP="${PUBLIC_IP:-}"
LETSENCRYPT_EMAIL="${LETSENCRYPT_EMAIL:-}"
PUBLIC_DOMAIN="${PUBLIC_DOMAIN,,}"

[[ "$PUBLIC_DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || \
  fail 'PUBLIC_DOMAIN must be a valid DNS hostname.'
[[ "$PUBLIC_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || \
  fail 'PUBLIC_IP must be a public IPv4 address.'
IFS='.' read -r -a public_ip_octets <<< "$PUBLIC_IP"
for octet in "${public_ip_octets[@]}"; do
  (( 10#$octet <= 255 )) || fail 'PUBLIC_IP contains an invalid IPv4 octet.'
done

[[ "$LETSENCRYPT_EMAIL" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || \
  fail 'LETSENCRYPT_EMAIL is missing or invalid.'
[[ -r "$SITE_TEMPLATE" ]] || fail "Missing domain HTTPS template: $SITE_TEMPLATE"
[[ -r "$RENEWAL_HOOK" ]] || fail "Missing Certbot renewal hook: $RENEWAL_HOOK"

# Bước 5: chọn cách chạy lệnh quản trị. Certificate private key và Nginx config
# thuộc root nên deploy user cần passwordless sudo.
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

# Bước 6: chỉ xin certificate khi DNS trên VPS đã nhìn thấy đúng public IP và
# backend hiện tại vẫn khỏe. Retry ngắn xử lý độ trễ propagation thông thường.
command -v nginx >/dev/null 2>&1 || fail 'Nginx must be provisioned before HTTPS.'
command -v curl >/dev/null 2>&1 || fail 'curl is required.'
command -v getent >/dev/null 2>&1 || fail 'getent is required for DNS validation.'

dns_ready='false'
for _ in {1..12}; do
  if getent ahostsv4 "$PUBLIC_DOMAIN" | awk '{print $1}' | grep -Fxq "$PUBLIC_IP"; then
    dns_ready='true'
    break
  fi
  sleep 5
done
[[ "$dns_ready" == 'true' ]] || \
  fail "$PUBLIC_DOMAIN does not resolve to $PUBLIC_IP from the VPS."

curl --fail --silent --show-error --max-time 10 --retry 3 \
  --retry-all-errors --retry-delay 1 \
  -H "Host: $PUBLIC_DOMAIN" http://127.0.0.1/healthz >/dev/null || \
  fail 'The current Nginx HTTP entry point is not ready.'

# Bước 7: dùng Certbot đã cài bởi flow IP HTTPS; nếu thiếu thì cài bản snap chính
# thức. Domain certificate không cần short-lived profile dành riêng cho IP.
if ! command -v snap >/dev/null 2>&1; then
  log 'Installing snapd for the official Certbot package.'
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get update
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y snapd
fi
root_cmd systemctl enable --now snapd.socket

if ! root_cmd snap list certbot >/dev/null 2>&1; then
  log 'Installing Certbot from the official snap channel.'
  root_cmd snap install --classic certbot
fi

if [[ -e /usr/local/bin/certbot && ! -L /usr/local/bin/certbot ]]; then
  fail '/usr/local/bin/certbot exists and is not a symlink; review it manually.'
fi
root_cmd ln -sfn /snap/bin/certbot /usr/local/bin/certbot

# Bước 8: cài renewal deploy hook rồi xin/reuse certificate bằng HTTP-01 webroot.
# Email không được in ra log và private key chỉ Certbot/root có quyền đọc.
root_cmd install -m 0755 -d /etc/letsencrypt/renewal-hooks/deploy /var/www/certbot
root_cmd install -m 0755 "$RENEWAL_HOOK" \
  /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

log "Requesting or reusing the certificate for $PUBLIC_DOMAIN."
root_cmd /usr/local/bin/certbot certonly \
  --non-interactive \
  --agree-tos \
  --email "$LETSENCRYPT_EMAIL" \
  --webroot \
  --webroot-path /var/www/certbot \
  --domains "$PUBLIC_DOMAIN" \
  --cert-name "$PUBLIC_DOMAIN" \
  --keep-until-expiring

domain_certificate_dir="/etc/letsencrypt/live/$PUBLIC_DOMAIN"
ip_certificate_dir="/etc/letsencrypt/live/$PUBLIC_IP"
root_cmd test -r "$domain_certificate_dir/fullchain.pem" || \
  fail 'Domain certificate chain was not created.'
root_cmd test -r "$domain_certificate_dir/privkey.pem" || \
  fail 'Domain certificate private key was not created.'
root_cmd test -r "$ip_certificate_dir/fullchain.pem" || \
  fail 'The existing IP certificate is missing.'
root_cmd test -r "$ip_certificate_dir/privkey.pem" || \
  fail 'The existing IP certificate private key is missing.'

# Bước 9: render config vào file tạm, backup site đang chạy và tự khôi phục nếu
# nginx -t thất bại. Hai endpoint HTTPS cùng tồn tại để chuyển đổi không downtime.
rendered_site="$(mktemp)"
backup_site="$(mktemp)"
had_previous_site='false'
cleanup() {
  rm -f "$rendered_site" "$backup_site"
}
trap cleanup EXIT

sed \
  -e "s/__PUBLIC_DOMAIN__/$PUBLIC_DOMAIN/g" \
  -e "s/__PUBLIC_IP__/$PUBLIC_IP/g" \
  "$SITE_TEMPLATE" > "$rendered_site"
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
  fail 'Domain HTTPS config is invalid; the previous site was restored.'
fi

# Bước 10: graceful reload rồi kiểm tra TLS/SAN và health qua loopback cho cả
# domain mới lẫn IP cũ trước khi coi quá trình chuyển đổi là thành công.
root_cmd systemctl reload nginx
curl --fail --silent --show-error --max-time 15 --retry 6 \
  --retry-all-errors --retry-delay 1 \
  --resolve "$PUBLIC_DOMAIN:443:127.0.0.1" \
  "https://$PUBLIC_DOMAIN/healthz" >/dev/null || \
  fail 'Local domain HTTPS validation failed.'
curl --fail --silent --show-error --max-time 15 --retry 6 \
  --retry-all-errors --retry-delay 1 \
  --connect-to "$PUBLIC_IP:443:127.0.0.1:443" \
  "https://$PUBLIC_IP/healthz" >/dev/null || \
  fail 'Existing IP HTTPS validation failed.'

# Bước 11: bật timer tự gia hạn và diễn tập renewal đúng certificate lineage.
root_cmd systemctl enable --now snap.certbot.renew.timer
root_cmd /usr/local/bin/certbot renew --dry-run --run-deploy-hooks \
  --cert-name "$PUBLIC_DOMAIN"

command -v openssl >/dev/null 2>&1 || fail 'openssl is required to inspect certificate expiry.'
certificate_expiry="$(root_cmd openssl x509 -enddate -noout \
  -in "$domain_certificate_dir/cert.pem")"
log "Domain HTTPS is ready; $certificate_expiry. Automatic renewal dry-run passed."

# FLOW TÓM TẮT:
# validate domain/IP/email -> đợi DNS trỏ đúng VPS -> kiểm tra HTTP/backend
# -> cài/reuse Certbot -> xin domain certificate -> render và test dual-host Nginx
# -> reload không downtime -> verify domain + IP TLS -> bật và dry-run renewal.
#
# VẤN ĐỀ GIẢI QUYẾT:
# cung cấp API URL ổn định có HTTPS tin cậy cho Vercel, tự gia hạn chứng chỉ và
# giữ endpoint IP cũ hoạt động trong lúc frontend chưa redeploy sang domain mới.
