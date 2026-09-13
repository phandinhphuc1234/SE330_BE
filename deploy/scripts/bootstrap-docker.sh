#!/usr/bin/env bash
# Mục đích: chuẩn bị một VPS Ubuntu/Debian mới để chạy Docker Compose production.
# Script này chỉ cài runtime container; không deploy ứng dụng và không đụng database.

# Bước 1: bật chế độ Bash nghiêm ngặt để dừng ngay khi lệnh lỗi, biến chưa khai
# báo hoặc một lệnh trong pipeline thất bại.
set -Eeuo pipefail

# Bước 2: chuẩn hóa log thành công/lỗi để GitHub Actions hiển thị dễ đọc.
log() {
  printf '[bootstrap] %s\n' "$*"
}

fail() {
  printf '[bootstrap] ERROR: %s\n' "$*" >&2
  exit 1
}

# Bước 3: chọn cách chạy lệnh quản trị. Root chạy trực tiếp; deploy user phải có
# passwordless sudo để workflow không bị treo chờ nhập mật khẩu.
if [[ "${EUID}" -eq 0 ]]; then
  root_cmd() {
    "$@"
  }
else
  command -v sudo >/dev/null 2>&1 || fail 'sudo is required for a non-root deploy user.'
  sudo -n true >/dev/null 2>&1 || fail 'The deploy user needs passwordless sudo for this one-time bootstrap.'
  root_cmd() {
    sudo -n "$@"
  }
fi

# Bước 4: đọc thông tin hệ điều hành và chỉ tiếp tục với Ubuntu/Debian đã hỗ trợ.
[[ -r /etc/os-release ]] || fail 'Cannot identify the operating system.'
# shellcheck disable=SC1091
source /etc/os-release

case "${ID:-}" in
  ubuntu|debian) ;;
  *) fail "Only Ubuntu and Debian are supported automatically (found ${ID:-unknown})." ;;
esac

[[ -n "${VERSION_CODENAME:-}" ]] || fail 'VERSION_CODENAME is missing from /etc/os-release.'
command -v dpkg >/dev/null 2>&1 || fail 'dpkg is required.'

# Bước 5: xác định tài khoản deploy để cấp quyền sử dụng Docker sau khi cài.
deploy_user="${SUDO_USER:-${USER:-}}"
[[ -n "$deploy_user" ]] || deploy_user="$(id -un)"

# Bước 6: nếu Docker đã có thì giữ nguyên. Nếu chưa có, kiểm tra package xung đột
# trước rồi cài Docker CE và Compose v2 từ repository chính thức của Docker.
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  log 'Docker Engine and Compose v2 are already installed.'
else
  conflicts=()
  for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
    if dpkg-query -W -f='${db:Status-Abbrev}' "$package" 2>/dev/null | grep -q '^ii '; then
      conflicts+=("$package")
    fi
  done

  if (( ${#conflicts[@]} > 0 )); then
    fail "Conflicting container packages detected: ${conflicts[*]}. Review and remove them manually before using Docker CE."
  fi

  log "Installing Docker CE from the official ${ID} stable repository."
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get update
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl
  root_cmd install -m 0755 -d /etc/apt/keyrings
  root_cmd curl -fsSL "https://download.docker.com/linux/${ID}/gpg" -o /etc/apt/keyrings/docker.asc
  root_cmd chmod a+r /etc/apt/keyrings/docker.asc

  architecture="$(dpkg --print-architecture)"
  printf '%s\n' \
    'Types: deb' \
    "URIs: https://download.docker.com/linux/${ID}" \
    "Suites: ${VERSION_CODENAME}" \
    'Components: stable' \
    "Architectures: ${architecture}" \
    'Signed-By: /etc/apt/keyrings/docker.asc' \
    | root_cmd tee /etc/apt/sources.list.d/docker.sources >/dev/null

  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get update
  root_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y \
    docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

# Bước 7: bật Docker/containerd khi khởi động máy và thêm deploy user vào group
# docker. Group này có quyền rất cao nên chỉ cấp cho tài khoản vận hành tin cậy.
root_cmd systemctl enable --now docker.service containerd.service

if [[ "$deploy_user" != 'root' ]]; then
  root_cmd groupadd --force docker
  root_cmd usermod -aG docker "$deploy_user"
  log "Added ${deploy_user} to the docker group; a new SSH login will activate it."
fi

# Bước 8: xác nhận Docker Engine và Compose thực sự hoạt động trước khi báo xong.
root_cmd docker version >/dev/null
root_cmd docker compose version >/dev/null
log "Docker bootstrap completed on ${PRETTY_NAME:-${ID}}."

# FLOW TÓM TẮT:
# kiểm tra quyền sudo -> xác nhận Ubuntu/Debian -> phát hiện package xung đột
# -> cài Docker CE/Compose -> bật service -> cấp quyền deploy user -> kiểm tra lại.
#
# VẤN ĐỀ GIẢI QUYẾT:
# biến một VPS mới thành máy chủ đủ điều kiện chạy stack production bằng Docker,
# theo cách lặp lại được và dừng an toàn khi môi trường không đúng kỳ vọng.
