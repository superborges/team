#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
compose=(docker compose -f "$repo_dir/deploy/compose.local.yml")
case "${1:-up}" in
  up) "${compose[@]}" up -d --wait --wait-timeout 180 ;;
  stop) "${compose[@]}" stop ;;
  status) "${compose[@]}" ps ;;
  logs) "${compose[@]}" logs --tail 100 ;;
  *) echo "Usage: $0 [up|stop|status|logs]" >&2; exit 2 ;;
esac
