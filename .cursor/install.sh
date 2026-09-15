#!/usr/bin/env bash
# Idempotent repository bootstrap for the Cloud Agent environment.
# Installs Docker (for the local MySQL/Redis infra), configures a nested-container
# friendly storage driver, installs frontend dependencies, and warms the Maven cache.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

# shellcheck source=.cursor/docker-lib.sh
. "$repo_dir/.cursor/docker-lib.sh"

log() { printf '\n=== %s ===\n' "$*"; }

# 1. Docker engine + compose plugin (skip when already present).
if ! command -v docker >/dev/null 2>&1; then
  log "Installing Docker engine"
  sudo install -m 0755 -d /etc/apt/keyrings
  if [[ ! -f /etc/apt/keyrings/docker.gpg ]]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg
  fi
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  log "Docker already installed: $(docker --version)"
fi

# 2. Use the vfs storage driver: overlayfs-on-overlayfs is unavailable inside the
#    nested container the agent runs in.
if [[ ! -f /etc/docker/daemon.json ]]; then
  log "Configuring Docker daemon (vfs storage driver)"
  sudo mkdir -p /etc/docker
  sudo bash -c 'cat > /etc/docker/daemon.json <<JSON
{
  "features": { "containerd-snapshotter": false },
  "storage-driver": "vfs"
}
JSON'
fi

# 3. Allow the agent user to reach the Docker socket without re-login.
sudo groupadd -f docker
sudo usermod -aG docker "$(id -un)" || true

# 4. Pre-pull the MySQL + Redis images so their layers are baked into the build
#    snapshot. With environment builds this runs once at build time, letting new
#    agents start the infra without a per-boot `docker pull`. Best-effort: if the
#    daemon cannot start during a build, start.sh will pull the images on boot.
log "Pre-pulling infrastructure images"
if ensure_dockerd; then
  docker compose -f deploy/compose.local.yml pull || echo "WARN: image pre-pull failed; start.sh will pull on boot."
else
  echo "WARN: Docker daemon unavailable during install; start.sh will pull images on boot."
fi

# 5. Frontend dependencies (npm workspaces).
log "Installing frontend dependencies"
npm --prefix frontend ci

# 6. Warm the Maven cache and produce an initial backend build so the first
#    start is fast. Uses the repo's serialized build wrapper.
log "Warming backend build"
python3 scripts/check-backend.py -q -DskipTests package

log "Install complete"
