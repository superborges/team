#!/usr/bin/env bash
# Per-boot runtime initialization: bring up the Docker daemon and the local
# MySQL + Redis infrastructure. Long-running app processes (API, worker, admin,
# h5) are launched from the `terminals` entries in environment.json.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

log() { printf '\n=== %s ===\n' "$*"; }

# 1. Start the Docker daemon if it is not already serving. There is no systemd
#    in this container, so run dockerd directly and detach it.
if ! docker info >/dev/null 2>&1; then
  log "Starting Docker daemon"
  sudo nohup dockerd >/tmp/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do
    if sudo docker info >/dev/null 2>&1; then break; fi
    sleep 1
  done
fi

# Make the socket reachable for the agent user in this dev VM.
if [[ -S /var/run/docker.sock ]]; then
  sudo chmod 666 /var/run/docker.sock || true
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon did not become ready; see /tmp/dockerd.log" >&2
  exit 1
fi
log "Docker ready: $(docker --version)"

# 2. Bring up MySQL + Redis (waits until both report healthy).
log "Starting local infrastructure (MySQL + Redis)"
./scripts/local-infra.sh up
./scripts/verify-infra.sh

log "Start complete"
