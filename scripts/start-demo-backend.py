#!/usr/bin/env python3
"""Run the prepared, isolated preview API or worker. Credentials stay outside the repo."""
import json
import os
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
os.chdir(root)
mode = sys.argv[1] if len(sys.argv) == 2 else ""
if mode not in {"api", "worker"}:
    raise SystemExit("Usage: python3 scripts/start-demo-backend.py api|worker")
config = json.loads((root / ".local/demo/config.json").read_text())
if config["database"] != "worklog_demo" or config["db_user"] != "worklog_demo":
    raise SystemExit("Refusing to run a demo against another database or database user.")
env = os.environ | {
    "DB_URL": "jdbc:mysql://127.0.0.1:13306/worklog_demo?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
    "DB_USER": config["db_user"], "DB_PASSWORD": config["db_password"],
    "SPRING_SESSION_REDIS_NAMESPACE": "worklog:demo:session",
    "SERVER_PORT": "8081", "SERVER_SERVLET_SESSION_COOKIE_NAME": "WORKLOG_DEMO_SESSION",
    "SERVER_SERVLET_SESSION_COOKIE_SECURE": "true",
    "APP_DEMO_ACCESS_ENABLED": "true" if mode == "api" else "false",
    "APP_DEMO_ACCESS_USER": config["access_user"],
    "APP_DEMO_ACCESS_PASSWORD": config["access_password"],
    "EXPORT_DIR": str(root / ".local/demo/exports"),
}
java = root / ".runtime/java/bin/java"
artifact = root / ".local/demo/backend.jar"
if not artifact.is_file():
    raise SystemExit("Prepare the demo artifact before starting its services.")
(root / f".local/demo/{mode}.pid").write_text(str(os.getpid()))
os.execve(java, [str(java), "-jar", str(artifact), f"--spring.profiles.active=local,{mode}"], env)
