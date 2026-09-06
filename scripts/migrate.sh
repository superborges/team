#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${DB_URL:?Set MySQL JDBC URL for the intended environment}"
: "${DB_MIGRATION_USER:?Set the dedicated migration user}"
: "${DB_MIGRATION_PASSWORD:?Inject migration password}"
if [[ -x "$repo_dir/.runtime/java/bin/java" ]]; then
  export JAVA_HOME="$repo_dir/.runtime/java"
fi
exec "$repo_dir/backend/mvnw" -B -ntp -f "$repo_dir/deploy/migration-pom.xml" flyway:migrate
