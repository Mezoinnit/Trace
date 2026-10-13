# Incident Response (Phase 14)

Operational runbook for the Trace backend. Companion to `DEPLOYMENT.md`
(deploy/backup) and `OBSERVABILITY.md` (metrics, health endpoints).

## Severity ladder

| Level | Definition | Examples | Response |
|-------|------------|----------|----------|
| **S1** | Data loss risk or cross-tenant exposure | tenant A sees tenant B data; database corruption; evidence history overwritten | Immediate: stop the affected service (`docker compose stop`), preserve state, engage owner |
| **S2** | Core function down | API 5xx for all users; worker not consuming; alerts not created | Within 1h: rollback or hotfix, communicate status |
| **S3** | Degraded / partial | single source unhealthy; elevated latency; queue depth growing | Same day |
| **S4** | Cosmetic / informational | stale parser notes; non-blocking warnings | Backlog |

## Roles

- **Incident lead**: coordinates, decides rollback vs. fix, owns comms.
- **Operator**: executes runbooks, watches `/metrics` and `/health/ready`.
- **Scribe**: timeline of actions and observations (needed for the postmortem).

## First-response checklist (any incident)

1. Classify severity (above). Default to the higher level when unsure.
2. Check `/health` (liveness) and `/health/ready` (readiness, database).
3. Check `/metrics`: `fetch_total{outcome=...}`, `job_queue_depth`,
   `alerts_created_total`. A frozen `job_queue_depth` with a live API means
   the worker is stuck — restart it (jobs are durable; nothing is lost,
   leases expire in 10 minutes per ADR-008).
4. If data is at risk, **stop writes first, investigate second**.
5. Record every action with a timestamp.

## Runbook: database failure / corruption

1. Stop API + worker (`docker compose stop api worker scheduler`).
2. Assess: is the failure connectivity (S3) or data corruption (S1)?
3. Connectivity: restart Postgres, `./gradlew :server:worker:run --args="--migrate-only"` is NOT needed for restarts — migrations are versioned and already applied; just restart services and verify `/health/ready`.
4. Corruption (S1):
   a. Snapshot current state for forensics: `pg_dump` the damaged database
      **before** any restore attempt.
   b. Pick the newest verified backup (`backend/scripts/backup.sh` output —
      each archive is verified with `pg_restore --list` at creation).
   c. `backend/scripts/restore.sh <dump> trace` (runs in a single transaction;
      rolls back entirely on failure).
   d. Verify: `/health/ready` responds `ready`; spot-check the newest
      evidence rows and `flyway_schema_history`.
   e. On restart, the scheduler will re-enqueue due fetches; ingestion is
      idempotent per `(endpoint_id, external_id)` (Rule 4), so replayed
      fetches never duplicate evidence or alerts.
5. Post-incident: file the postmortem within 3 business days; a backup that
   has never been restore-tested does not count as a backup — run the drill
   quarterly (DEPLOYMENT.md §7).

## Runbook: suspected source abuse (SSRF attempt / poisoned feed)

1. Find the endpoint: query `source_health` for `BLOCKED` states and
   `audit_log` for `SOURCE_FETCH_FAILED` with state `BLOCKED`.
2. The SSRF guard blocks private/metadata ranges by resolved IP and
   revalidates every redirect (SECURITY.md §3, ADR-011) — a blocked fetch is
   expected behavior, but repeated attempts are a signal: disable the source
   (`PATCH /api/v1/sources/{id}` status `DISABLED`).
3. If bad content *was* ingested (parser accepted it): evidence is immutable
   and versioned; mark the event `CONTESTED` (never delete, Rule 5), fix the
   adapter, re-derive.
4. Escalate to S1 if any untrusted content reached the AI layer with
   instruction-like content and its output was recorded as an event
   (prompt-injection containment: verify `ai_operations` for the input_ref).

## Runbook: credential leak (JWT secret / database password)

1. Rotate the leaked secret (`backend/.env`); never commit secrets (AGENTS.md).
2. JWT secret rotation invalidates all access tokens (users re-authenticate);
   refresh tokens stay valid (hashed in `refresh_tokens`, separate secret
   family). If refresh tokens are implicated: revoke all families
   (`UPDATE refresh_tokens SET status='REVOKED', revoked_at=now()`), force
   re-login everywhere.
3. Database password rotation: change in Postgres, update `backend/.env`,
   restart services. Rolling restart is safe (Hikari re-authenticates).
4. Audit: query `audit_log` for anomalies in the exposure window.

## Escalation and comms

- S1/S2: notify the workspace owners if their data was affected (honesty is
  a product requirement, plan §53; Rule 7 applies to incidents too).
- Postmortems are blameless and public to the team; every S1/S2 gets one.

## Metrics that page (OBSERVABILITY.md Phase 14)

| Signal | Threshold | Meaning |
|--------|-----------|---------|
| `/health/ready` non-200 | any | database down — S2 |
| `job_queue_depth` | rising for > 30 min | worker stuck/slow — S3 |
| `fetch_total{outcome="network_failed"}` | sustained spike | egress/network incident — S3 |
| `fetch_total{outcome="blocked"}` | any sustained > 0 | SSRF attempts — investigate source |
| `evidence_versioned_total` | unusual spike | sources rewriting content — verify adapters (Rule 5) |
