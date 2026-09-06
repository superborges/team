#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
compose=(docker compose -f "$repo_dir/deploy/compose.local.yml")
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT VERSION() AS mysql_version, @@default_storage_engine AS engine, @@character_set_server AS charset; SELECT 1 AS connection_ok;"'
"${compose[@]}" exec -T redis redis-cli ping
"${compose[@]}" exec -T redis redis-cli INFO server | sed -n '/^redis_version:/p'
