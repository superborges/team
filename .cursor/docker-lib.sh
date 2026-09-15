#!/usr/bin/env bash
# Shared helper: ensure the Docker daemon is running. There is no systemd in this
# container, so dockerd is launched directly and detached. The vfs storage driver
# (configured in install.sh) is required because overlayfs-on-overlayfs is
# unavailable inside the nested container the agent runs in.

ensure_dockerd() {
  if docker info >/dev/null 2>&1; then
    return 0
  fi
  sudo nohup dockerd >/tmp/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do
    if sudo docker info >/dev/null 2>&1; then break; fi
    sleep 1
  done
  # Make the socket reachable for the agent user in this dev VM.
  if [[ -S /var/run/docker.sock ]]; then
    sudo chmod 666 /var/run/docker.sock || true
  fi
  docker info >/dev/null 2>&1
}
