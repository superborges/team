#!/usr/bin/env python3
"""Local-only consistent dump/isolated restore. Keeps the live DB, backup, and restored DB intact."""
import collections
import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ['docker', 'compose', '-p', 'allen-worklog', '-f', str(ROOT / 'deploy/compose.local.yml'), 'exec', '-T', 'mysql']

def command(program, *args):
    return COMPOSE + ['sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec "$@"', '--', program, '-uroot', *args]

def sql(database, query):
    result = subprocess.run(command('mysql', '--batch', '--raw', '--skip-column-names', database, '-e', query), capture_output=True, text=True, check=True)
    return result.stdout.strip()

def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()

def main():
    if sql('worklog', 'SELECT wecom_userid FROM app_user WHERE id=4') != 'local-admin':
        raise SystemExit('Refusing: expected explicitly seeded local worklog database.')
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    restored = 'worklog_restore_check_' + stamp.lower().replace('t', '_').replace('z', '') + '_' + uuid.uuid4().hex[:5]
    assert re.fullmatch('[a-z0-9_]+', restored)
    backup_dir = ROOT / '.local' / 'backups'
    backup_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(backup_dir, 0o700)
    archive = backup_dir / ('worklog-' + stamp + '.sql.gz')
    before_schema = sql('worklog', 'SELECT installed_rank,version,description,COALESCE(checksum,0),success FROM flyway_schema_history ORDER BY installed_rank')
    started = sql('worklog', 'SELECT UTC_TIMESTAMP(6)')
    proc = subprocess.Popen(command('mysqldump', '--single-transaction', '--quick', '--routines', '--triggers', '--events', '--hex-blob', '--skip-extended-insert', '--skip-comments', '--no-tablespaces', '--set-gtid-purged=OFF', 'worklog'), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    with gzip.open(archive, 'wb') as output:
        while chunk := proc.stdout.read(1024 * 1024):
            output.write(chunk)
    error = proc.stderr.read().decode()
    if proc.wait() != 0:
        raise RuntimeError('mysqldump failed: ' + error)
    os.chmod(archive, 0o600)
    if before_schema != sql('worklog', 'SELECT installed_rank,version,description,COALESCE(checksum,0),success FROM flyway_schema_history ORDER BY installed_rank'):
        raise RuntimeError('Schema changed while dumping; retry after migrations finish. Backup retained for inspection.')
    expected = collections.Counter()
    with gzip.open(archive, 'rt') as source:
        for line in source:
            if match := re.match(r'^CREATE TABLE `([^`]+)`', line):
                expected[match[1]] += 0
            elif match := re.match(r'^INSERT INTO `([^`]+)` VALUES \(', line):
                expected[match[1]] += 1
    sql('worklog', f'CREATE DATABASE `{restored}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    proc = subprocess.Popen(command('mysql', restored), stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    with gzip.open(archive, 'rb') as source:
        while chunk := source.read(1024 * 1024):
            proc.stdin.write(chunk)
    proc.stdin.close()
    proc.stdin = None
    _, error = proc.communicate()
    if proc.returncode:
        raise RuntimeError('Restore failed; isolated database retained: ' + error.decode())
    actual = {table: int(sql(restored, f'SELECT COUNT(*) FROM `{table}`')) for table in expected}
    if dict(expected) != actual:
        raise RuntimeError('Restored counts differ from the consistent dump: ' + json.dumps({'dump': dict(expected), 'restored': actual}))
    versions = sql(restored, 'SELECT DISTINCT version_id FROM monthly_snapshot_row ORDER BY version_id').splitlines()
    if versions and all(re.fullmatch('[0-9]+', value) for value in versions):
        query = "SELECT CONCAT(id,'|',SHA2(CONCAT_WS('|',version_id,row_kind,COALESCE(CAST(user_id AS CHAR),''),COALESCE(CAST(work_date AS CHAR),''),COALESCE(CAST(work_item_id AS CHAR),''),COALESCE(CAST(record_id AS CHAR),''),COALESCE(CAST(revision_id AS CHAR),''),CAST(payload AS CHAR)),256)) FROM monthly_snapshot_row WHERE version_id IN (" + ','.join(versions) + ') ORDER BY id'
        original_rows = sql('worklog', query)
        restored_rows = sql(restored, query)
        if original_rows != restored_rows:
            raise RuntimeError('Published snapshot content differs between original and restored database.')
        snapshot_hash = digest_bytes(original_rows.encode())
    else:
        snapshot_hash = digest_bytes(b'')
    hasher = hashlib.sha256()
    with archive.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            hasher.update(chunk)
    result = {'startedAtUtc': started, 'finishedAtUtc': sql('worklog', 'SELECT UTC_TIMESTAMP(6)'), 'sourceDatabase': 'worklog', 'restoredDatabase': restored, 'backup': str(archive), 'backupSha256': hasher.hexdigest(), 'compressedBytes': archive.stat().st_size, 'tableCount': len(actual), 'rowCounts': actual, 'snapshotVersionIds': versions, 'snapshotCanonicalSha256': snapshot_hash, 'schemaHistorySha256': digest_bytes(before_schema.encode()), 'result': 'PASS'}
    manifest = archive.with_suffix('.verification.json')
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    os.chmod(manifest, 0o600)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print('Manifest:', manifest)

if __name__ == '__main__':
    main()
