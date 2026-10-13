#!/usr/bin/env bash
# One-time server provisioning for Trace on a bare Ubuntu VPS
# (designed for Oracle Cloud Always Free Ampere A1; idempotent, safe to re-run).
#
# Usage: sudo ./setup.sh <hostname>
#   <hostname> = public DNS name pointed at this machine's IP before running,
#   e.g. trace-yourname.duckdns.org (see DEPLOYMENT.md §6).
#
# What it does: installs OpenJDK 17, PostgreSQL 16, Caddy, ufw; creates the
# `trace` system user and database; writes /etc/trace/env (generated secrets
# stay on this machine, never in git); clones the repo, builds the API
# distribution, and enables the systemd units + Caddy + backup timer.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "run as root: sudo $0 <hostname>" >&2
  exit 1
fi
HOST="${1:-}"
if [[ -z "$HOST" ]]; then
  echo "usage: sudo $0 <hostname>   (e.g. trace-you.duckdns.org)" >&2
  exit 1
fi

REPO_URL="https://github.com/Mezoinnit/Trace.git"
APP_DIR="/opt/trace"
ENV_DIR="/etc/trace"
ENV_FILE="$ENV_DIR/env"
DB_PASS_FILE="$ENV_DIR/.dbpass"

echo "==> packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y openjdk-17-jdk postgresql caddy git ufw curl

echo "==> trace system user"
if ! id -u trace >/dev/null 2>&1; then
  useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin trace
fi

echo "==> database (bound to localhost only)"
mkdir -p "$ENV_DIR"
if [[ ! -f "$DB_PASS_FILE" ]]; then
  tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 >"$DB_PASS_FILE"
  chmod 600 "$DB_PASS_FILE"
  chown root:root "$DB_PASS_FILE"
fi
DB_PASS="$(cat "$DB_PASS_FILE")"
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='trace'" | grep -q 1; then
  sudo -u postgres psql -c "CREATE ROLE trace LOGIN PASSWORD '$DB_PASS'"
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='trace'" | grep -q 1; then
  sudo -u postgres createdb -O trace trace
fi

echo "==> $ENV_FILE (generated once; re-runs never overwrite it)"
if [[ ! -f "$ENV_FILE" ]]; then
  JWT_SECRET="$(openssl rand -base64 48)"
  umask 077
  cat >"$ENV_FILE" <<EOF
DATABASE_URL=jdbc:postgresql://localhost:5432/trace
DATABASE_USER=trace
DATABASE_PASSWORD=$DB_PASS
DATABASE_MAX_POOL_SIZE=5
PORT=8080
EMBED_WORKER=true
TRACE_ENV=production
JWT_SECRET=$JWT_SECRET
TRACE_HSTS_ENABLED=true
AI_PROVIDER=local
EOF
  chmod 600 "$ENV_FILE"
  chown root:root "$ENV_FILE"
fi

echo "==> clone + build"
if [[ ! -d "$APP_DIR/.git" ]]; then
  git clone "$REPO_URL" "$APP_DIR"
fi
chown -R trace:trace "$APP_DIR"
sudo -u trace bash -c "cd '$APP_DIR/backend' && ./gradlew --no-daemon :server:api:installDist"

echo "==> systemd units + Caddyfile"
install -m 644 "$APP_DIR/backend/deploy/trace.service" /etc/systemd/system/trace.service
install -m 644 "$APP_DIR/backend/deploy/trace-backup.service" /etc/systemd/system/trace-backup.service
install -m 644 "$APP_DIR/backend/deploy/trace-backup.timer" /etc/systemd/system/trace-backup.timer
if [[ ! -f /etc/caddy/Caddyfile ]]; then
  sed "s/trace\.example\.duckdns\.org/$HOST/g" "$APP_DIR/backend/deploy/Caddyfile" > /etc/caddy/Caddyfile
fi
mkdir -p /var/backups/trace
chown postgres:postgres /var/backups/trace
systemctl daemon-reload
systemctl enable --now trace
systemctl enable --now trace-backup.timer
systemctl enable --now caddy
systemctl reload caddy 2>/dev/null || systemctl restart caddy

echo "==> firewall (SSH + HTTP/HTTPS only; Postgres stays on localhost)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo
echo "done. verify:  curl https://$HOST/health/ready"
