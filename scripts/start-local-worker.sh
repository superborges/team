#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
if [[ -x "$repo_dir/.runtime/java/bin/java" ]]; then export JAVA_HOME="$repo_dir/.runtime/java"; fi
if [[ ! -f .local/run/api-artifact.path ]]; then
  echo '请先运行 scripts/start-local-api.sh，再启动同一制品的 worker。' >&2
  exit 1
fi
api_artifact="$(cat .local/run/api-artifact.path)"
if [[ ! -f "$api_artifact" ]]; then
  echo 'API 制品快照不存在，请重新运行 scripts/start-local-api.sh。' >&2
  exit 1
fi
mkdir -p .local/run
artifact="$(mktemp "$repo_dir/.local/run/worklog-worker-XXXXXX")"
cp "$api_artifact" "$artifact"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$artifact" --spring.profiles.active=local,worker
