#!/usr/bin/env python3
"""Start the prepared isolated preview in Docker, independent of the development session."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
demo = root / ".local/demo"
os.umask(0o077)
config = json.loads((demo / "config.json").read_text())
if config["database"] != "worklog_demo" or config["db_user"] != "worklog_demo":
    raise SystemExit("Refusing to use another database for the preview.")
if not (demo / "backend.jar").is_file():
    raise SystemExit("Prepare .local/demo/backend.jar before starting.")
settings = {
    "DB_URL": "jdbc:mysql://host.docker.internal:13306/worklog_demo?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
    "DB_USER": config["db_user"], "DB_PASSWORD": config["db_password"],
    "REDIS_HOST": "host.docker.internal", "REDIS_PORT": "16379",
    "SPRING_SESSION_REDIS_NAMESPACE": "worklog:demo:session",
    "SERVER_SERVLET_SESSION_COOKIE_NAME": "WORKLOG_DEMO_SESSION",
    "SERVER_SERVLET_SESSION_COOKIE_SECURE": "true",
    "APP_DEMO_ACCESS_USER": config["access_user"], "APP_DEMO_ACCESS_PASSWORD": config["access_password"],
    "EXPORT_DIR": "/app/exports",
}
if any("\n" in value or "\r" in value for value in settings.values()):
    raise SystemExit("Demo settings must be single-line values.")
file = demo / "backend.env"
file.write_text("\n".join(f"{key}={value}" for key, value in settings.items()) + "\n")
file.chmod(0o600)
env = os.environ | {"DEMO_RUN_AS": f"{os.getuid()}:{os.getgid()}"}
compose = ["docker", "compose", "-f", str(root / "deploy/compose.demo.yml")]
subprocess.run(["docker", "compose", "-f", str(root / "deploy/compose.local.yml"), "up", "-d", "mysql", "redis"], check=True)
subprocess.run([*compose, "up", "-d"], env=env, check=True)
subprocess.run([*compose, "exec", "-T", "web", "nginx", "-t"], check=True)
subprocess.run([*compose, "exec", "-T", "web", "nginx", "-s", "reload"], check=True)
# Reusing a running container preserves its URL. Only read logs from its current start.
container = subprocess.check_output([*compose, "ps", "-q", "tunnel"], text=True).strip()
started = subprocess.check_output(["docker", "inspect", "--format", "{{.State.StartedAt}}", container], text=True).strip()
deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    try:
        with urllib.request.urlopen("http://127.0.0.1:8090/demo-access/login?target=h5", timeout=2) as response:
            assert response.status == 200
        with urllib.request.urlopen("http://127.0.0.1:20242/ready", timeout=2) as response:
            assert json.load(response)["readyConnections"] > 0
        logs = subprocess.run(["docker", "logs", "--since", started, container], capture_output=True, text=True, check=True)
        matches = re.findall(r"https://[a-z0-9-]+\.trycloudflare\.com", logs.stdout + logs.stderr)
        if not matches:
            raise OSError("Waiting for tunnel URL")
        base = matches[-1]
        share = (f"工时与投入 MVP 演示\n\n电脑端：{base}/admin/\n手机端：{base}/h5/\n\n"
                 f"访问账号：{config['access_user']}\n访问口令：{config['access_password']}\n\n"
                 "先在网页内输入账号、口令，再选择测试身份。98123 为员工，XX12345 为项目经理，ZD23412 为部门负责人，00123 为管理员。\n"
                 "仅使用独立测试数据。电脑和 Docker 需保持运行联网；临时隧道重建会换地址。\n")
        for name, value in (("url.txt", base + "\n"), ("分享说明.txt", share)):
            pending = demo / f"{name}.tmp"
            pending.write_text(value)
            pending.replace(demo / name)
        print(base)
        break
    except (OSError, AssertionError):
        time.sleep(1)
else:
    raise SystemExit("Demo is still starting. Inspect docker compose -f deploy/compose.demo.yml logs --tail 30.")
