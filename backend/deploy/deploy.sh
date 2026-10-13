#!/usr/bin/env bash
# Update a running Trace deployment on this server: pull, rebuild the API
# distribution, restart, verify health. Run as root:
#   sudo /opt/trace/backend/deploy/deploy.sh
set -euo pipefail

APP_DIR="/opt/trace"

sudo -u trace git -C "$APP_DIR" pull --ff-only
sudo -u trace bash -c "cd '$APP_DIR/backend' && ./gradlew --no-daemon :server:api:installDist"
systemctl restart trace

for _ in $(seq 1 30); do
  if curl -fsS http://localhost:8080/health/ready >/dev/null 2>&1; then
    echo "deploy ok — healthy"
    exit 0
  fi
  sleep 2
done

echo "deploy finished but /health/ready did not come up in 60s" >&2
echo "check: journalctl -u trace -n 50" >&2
exit 1
