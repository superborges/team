#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
if [[ "$(uname -s)/$(uname -m)" != Darwin/arm64 ]]; then
  echo 'This bootstrap downloads the verified macOS Apple Silicon runtime. On other systems provide Java 21 and use backend/mvnw.' >&2
  exit 2
fi
mkdir -p .runtime/downloads
python3 - <<'PY'
import hashlib
import pathlib
import subprocess

runtime = pathlib.Path('.runtime')
artifacts = [
    ('temurin21.tar.gz',
     'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz',
     'sha256', '3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998',
     'jdk-21.0.12.1+1/Contents/Home', 'java'),
    ('maven.tar.gz',
     'https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz',
     'sha512', 'bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978',
     'apache-maven-3.9.11', 'maven'),
]
for filename, url, algorithm, checksum, target, alias in artifacts:
    archive = runtime / 'downloads' / filename
    if not archive.exists():
        subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--silent', '--show-error', url, '-o', str(archive)], check=True)
    if hashlib.new(algorithm, archive.read_bytes()).hexdigest() != checksum:
        raise SystemExit(f'Checksum mismatch: {archive}; remove only this archive and retry.')
    if not (runtime / target).exists():
        subprocess.run(['tar', '-xzf', str(archive), '-C', str(runtime)], check=True)
    link = runtime / alias
    if link.is_symlink() and link.readlink() == pathlib.Path(target):
        continue
    if link.exists() or link.is_symlink():
        raise SystemExit(f'Will not replace existing path: {link}')
    link.symlink_to(target)
print('Verified local Java 21 and Maven runtime.')
PY
"$repo_dir/.runtime/java/bin/java" -version
JAVA_HOME="$repo_dir/.runtime/java" "$repo_dir/.runtime/maven/bin/mvn" -version
