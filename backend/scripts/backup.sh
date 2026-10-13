#!/usr/bin/env bash
# Trace database backup (DEPLOYMENT.md §7, Phase 14).
#
# Creates a compressed custom-format dump (pg_dump -Fc) of the trace
# database and prunes old backups beyond TRACE_BACKUP_RETENTION.
#
# Usage: ./backup.sh [output-dir]
# Environment:
#   DATABASE_URL      jdbc url is parsed for host/port/db; or set PG* vars directly
#   PGHOST PGPORT PGUSER PGPASSWORD   standard libpq variables
#   TRACE_BACKUP_RETENTION  number of backups to keep (default 14)
set -euo pipefail

OUTPUT_DIR="${1:-${TRACE_BACKUP_DIR:-./backups}}"
RETENTION="${TRACE_BACKUP_RETENTION:-14}"

# Parse DATABASE_URL if PG* variables are not already set.
if [[ -z "${PGHOST:-}" && -n "${DATABASE_URL:-}" ]]; then
  regexp='jdbc:postgresql://([^:/]+):?([0-9]*)/([^?]+)'
  if [[ "${DATABASE_URL}" =~ $regexp ]]; then
    export PGHOST="${BASH_REMATCH[1]}"
    export PGPORT="${BASH_REMATCH[2]:-5432}"
    export PGDATABASE="${BASH_REMATCH[3]}"
  else
    echo "ERROR: cannot parse DATABASE_URL" >&2
    exit 1
  fi
fi

: "${PGDATABASE:=trace}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"

mkdir -p "${OUTPUT_DIR}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="${OUTPUT_DIR}/trace-${PGDATABASE}-${STAMP}.dump"

echo "Dumping ${PGDATABASE}@${PGHOST}:${PGPORT} -> ${TARGET}"
pg_dump \
  --format=custom \
  --compress=6 \
  --no-owner \
  --no-privileges \
  --file="${TARGET}"

# Verify the archive is readable before declaring success (Rule 5: a silent
# corrupt backup is worse than none).
pg_restore --list "${TARGET}" > /dev/null
echo "Archive verified: $(du -h "${TARGET}" | cut -f1)"

# Retention: keep the newest RETENTION dumps, remove older ones.
ls -1t "${OUTPUT_DIR}"/trace-*.dump 2>/dev/null | tail -n +"$((RETENTION + 1))" | while read -r old; do
  echo "Pruning old backup: ${old}"
  rm -f "${old}"
done

echo "Backup complete."
