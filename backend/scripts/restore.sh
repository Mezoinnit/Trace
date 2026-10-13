#!/usr/bin/env bash
# Trace database restore (DEPLOYMENT.md §7, Phase 14).
#
# Restores a custom-format dump created by backup.sh into a (fresh) database
# and verifies connectivity afterwards.
#
# Usage: ./restore.sh <backup-file> [target-database]
# Environment: PGHOST PGPORT PGUSER PGPASSWORD (libpq standard)
set -euo pipefail

DUMP_FILE="${1:?usage: restore.sh <backup-file> [target-database]}"
TARGET_DB="${2:-trace}"

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "ERROR: backup file not found: ${DUMP_FILE}" >&2
  exit 1
fi

# Parse DATABASE_URL for connection info when PG* vars are absent.
if [[ -z "${PGHOST:-}" && -n "${DATABASE_URL:-}" ]]; then
  regexp='jdbc:postgresql://([^:/]+):?([0-9]*)/([^?]+)'
  if [[ "${DATABASE_URL}" =~ $regexp ]]; then
    export PGHOST="${BASH_REMATCH[1]}"
    export PGPORT="${BASH_REMATCH[2]:-5432}"
  fi
fi

: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"

echo "Restoring ${DUMP_FILE} -> ${TARGET_DB}@${PGHOST}:${PGPORT}"
pg_restore \
  --dbname="${TARGET_DB}" \
  --clean \
  --if-exists \
  --no-owner \
  --no-privileges \
  --single-transaction \
  "${DUMP_FILE}"

# Post-restore verification: migrations table must exist and be populated.
if psql --dbname="${TARGET_DB}" --tuples-only --no-align \
    --command="SELECT count(*) > 0 FROM flyway_schema_history;" | grep -q t; then
  echo "Restore verified: schema history present."
else
  echo "ERROR: restore verification failed (flyway_schema_history missing/empty)" >&2
  exit 1
fi

echo "Restore complete."
