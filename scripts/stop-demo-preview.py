#!/usr/bin/env python3
"""Stop this workspace's external preview without stopping the normal local MVP."""
import os
from pathlib import Path
import signal
import subprocess

root = Path(__file__).resolve().parents[1]
# Explicit stop suppresses Docker's unless-stopped restart policy.
subprocess.run(["docker", "compose", "-f", str(root / "deploy/compose.demo.yml"), "stop"], check=True)
# Also clean up verified processes from the earlier terminal-based preview.
for name in ("tunnel", "api", "worker"):
    file = root / f".local/demo/{name}.pid"
    if not file.exists():
        continue
    pid = int(file.read_text())
    command = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True).stdout
    expected = ("cloudflared" in command and "--url http://127.0.0.1:8090" in command) if name == "tunnel" else str(root / ".local/demo/backend.jar") in command
    if expected:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        print(f"Stopped demo {name}.")
    elif command.strip():
        print(f"Skipped stale {name} PID; it now belongs to another process.")
    file.unlink()
for name in ("url.txt", "分享说明.txt"):
    (root / ".local/demo" / name).unlink(missing_ok=True)
