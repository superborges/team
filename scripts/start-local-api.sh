#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
if [[ -x "$repo_dir/.runtime/java/bin/java" ]]; then export JAVA_HOME="$repo_dir/.runtime/java"; fi
mkdir -p .local/run
artifact="$(mktemp "$repo_dir/.local/run/worklog-api-XXXXXX")"
WORKLOG_LOCAL_SNAPSHOT="$artifact" python3 scripts/check-backend.py -DskipTests package
pointer="$(mktemp "$repo_dir/.local/run/api-artifact-path-XXXXXX")"
printf '%s\n' "$artifact" > "$pointer"
mv "$pointer" .local/run/api-artifact.path
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT="${SERVER_PORT:-8080}"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$artifact" --spring.profiles.active=local,api
