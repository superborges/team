#!/usr/bin/env python3
"""Serialize Maven builds sharing target/ while parallel development is active."""
import fcntl
import os
from pathlib import Path
import subprocess
import shutil
import sys

root = Path(__file__).resolve().parents[1]
(root / '.local').mkdir(exist_ok=True)
env = dict(os.environ)
if 'JAVA_HOME' not in env and (root / '.runtime/java/bin/java').exists():
    env['JAVA_HOME'] = str(root / '.runtime/java')
with (root / '.local/maven-build.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    code = subprocess.call([str(root / 'backend/mvnw'), '-B', '-ntp', '-f', str(root / 'backend/pom.xml'), *sys.argv[1:]], cwd=root, env=env)
    # Startup snapshots are copied before releasing the build lock, never while another package overwrites the JAR.
    if code == 0 and env.get('WORKLOG_LOCAL_SNAPSHOT'):
        snapshot = Path(env['WORKLOG_LOCAL_SNAPSHOT']).resolve()
        if snapshot.parent != (root / '.local/run').resolve():
            raise SystemExit('Local startup snapshot must be inside .local/run')
        shutil.copyfile(root / 'backend/target/worklog-0.1.0-SNAPSHOT.jar', snapshot)
    raise SystemExit(code)
