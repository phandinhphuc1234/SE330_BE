#!/usr/bin/env bash
set -Eeuo pipefail

log() {
  printf '[bootstrap] %s\n' "$*"
}

fail() {
  printf '[bootstrap] ERROR: %s\n' "$*" >&2
  exit 1
}

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

[[ -r /etc/os-release ]] || fail 'Cannot identify the operating system.'
# shellcheck disable=SC1091
source /etc/os-release

case "${ID:-}" in
  ubuntu|debian) ;;
  *) fail "Only Ubuntu and Debian are supported automatically (found ${ID:-unknown})." ;;
esac

[[ -n "${VERSION_CODENAME:-}" ]] || fail 'VERSION_CODENAME is missing from /etc/os-release.'
command -v dpkg >/dev/null 2>&1 || fail 'dpkg is required.'

deploy_user="${SUDO_USER:-${USER:-}}"
[[ -n "$deploy_user" ]] || deploy_user="$(id -un)"

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

root_cmd systemctl enable --now docker.service containerd.service

if [[ "$deploy_user" != 'root' ]]; then
  root_cmd groupadd --force docker
  root_cmd usermod -aG docker "$deploy_user"
  log "Added ${deploy_user} to the docker group; a new SSH login will activate it."
fi

root_cmd docker version >/dev/null
root_cmd docker compose version >/dev/null
log "Docker bootstrap completed on ${PRETTY_NAME:-${ID}}."
