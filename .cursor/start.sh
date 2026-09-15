#!/usr/bin/env bash
# Per-boot runtime initialization: bring up the Docker daemon and the local
# MySQL + Redis infrastructure. Long-running app processes (API, worker, admin,
# h5) are launched from the `terminals` entries in environment.json.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

# shellcheck source=.cursor/docker-lib.sh
. "$repo_dir/.cursor/docker-lib.sh"

log() { printf '\n=== %s ===\n' "$*"; }

# 1. Start the Docker daemon if it is not already serving.
log "Starting Docker daemon"
if ! ensure_dockerd; then
  echo "Docker daemon did not become ready; see /tmp/dockerd.log" >&2
  exit 1
fi
log "Docker ready: $(docker --version)"

# 2. Bring up MySQL + Redis (waits until both report healthy).
log "Starting local infrastructure (MySQL + Redis)"
./scripts/local-infra.sh up
./scripts/verify-infra.sh

log "Start complete"
