# Phase 14 — Security and Production Hardening (Implementation Plan)

> Status: APPROVED by user ("yes proceed"). Phases 0–13 complete at commit
> `6ec7465`. This phase closes the remaining gaps per master plan §Phase 14.

## Goal

Prepare Trace for real users: harden the surface, prove the security
properties with tests, and make operations observable and recoverable.

## Verified gaps (audited against code at 6ec7465)

Already implemented in Phases 1–13 (verify, don't rebuild): SSRF guard with
IP validation + redirect revalidation (`SsrfGuard.kt`, `SsrfGuardTest.kt`),
tenant isolation matrix, IDOR itests, audit log, argon2id + refresh rotation,
rate limiting (auth + API), exactly-once alerts.

Gaps to close:

1. **Secure headers** — `ApiApplication.kt` installs only
   ContentNegotiation/CallId/CallLogging/StatusPages. No HSTS,
   `X-Content-Type-Options`, `X-Frame-Options`, Referrer-Policy; no request
   body size limit.
2. **RLS (row-level security)** — deferred to Phase 14 by ADR-015 decision.
3. **Metrics** — no counters anywhere; OBSERVABILITY.md promises
   `fetch_*`, `evidence_created`, `alerts_created`, queue depth with Phase 8.
4. **Mandatory security test matrix** — missing: DNS rebinding (live
   re-resolve), oversized payload, redirect abuse (live), malformed content,
   auth abuse/enumeration, rate-limit bypass, duplicate notification,
   job replay, data corruption.
5. **Abuse prevention** — no per-workspace source quota (Rule 6 bound).
6. **Supply chain** — CI has no dependency scanning (Dependabot) and no
   static analysis (detekt).
7. **Backup/restore + DR** — DEPLOYMENT.md §7 says "Phase 14"; nothing
   implemented.
8. **Incident procedures / alerting** — not documented.
9. **Secret management verification** — log redaction unproven by test.

## Steps (ordered, each ends green)

> Status: Steps 1–8 implemented and green (metrics live, matrix tests green,
> detekt baselined, CI + Dependabot wired, docs updated). Residual follow-ups
> completed: `events_created_total` counter, `jobs_dead_lettered` gauge,
> compose `pg_backup` service. Remaining: close-out full build (backend +
> Android) — rerun after the residual additions.

- **Step 1 — SecureHeaders + payload bounds.** ✅ New `SecureHeaders.kt` plugin
  in `server/api`: HSTS behind config flag (`TRACE_SECURE_HEADERS_HSTS`,
  off for local http dev), `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store` on auth responses; request body size limit via
  `ApplicationCall.receive` guard (`TRACE_MAX_BODY_BYTES`, default 1 MiB).
  Unit tests assert presence/value on `/health/ready` and API routes.
- **Step 2 — Postgres RLS.** Migration `V16__rls_tenant_isolation.sql`:
  enable RLS on workspace-owned tables, policies keyed on
  `current_setting('trace.workspace_id')`, forced for table owner,
  connection uses a non-superuser role. App sets the GUC per
  transaction. Itest: direct JDBC query for another workspace's rows
  returns empty even bypassing the application layer.
- **Step 3 — Metrics.** Micrometer `micrometer-core` + simple in-process
  registry (no paid deps, no Prometheus server needed for $0 dev):
  `Metrics.kt` in a shared location reachable by worker/scheduler/api —
  counters `fetch_total{outcome}`, `evidence_created`, `events_created`,
  `alerts_created`, `notifications_dispatched{mode}`, gauge
  `job_queue_depth`, `jobs_dead_lettered`. Expose `/metrics` (text/plain)
  on the API. Tests: counter increments on alert dispatch and fetch.
- **Step 4 — Security test matrix** (`Phase14SecurityITest.kt` +
  fetch-module tests): DNS rebinding TOCTOU re-resolve; oversized payload
  → 413; redirect abuse (live loop private IP); malformed feed →
  parser-failed health, no crash; auth abuse (wrong password ×N rate
  limited, no account enumeration — same error body for unknown user);
  rate-limit bypass attempt (rotate IP → still limited per user);
  duplicate notification under replay; job replay safety; data
  corruption (evidence hash mismatch → versioned, never overwritten).
- **Step 5 — Abuse prevention.** Per-workspace source cap (default 100,
  config `TRACE_MAX_SOURCES_PER_WORKSPACE`) enforced in
  `CreateSourceUseCase` with clear error; test. **Deferred (ADR-001):**
  sources are deliberately global shared intelligence with no tenant
  column, so a per-workspace cap has no attachment point and would add
  tracking machinery for a cap on an unbounded global feed. The shipped
  bounds that answer the same abuse surface: per-workspace watch cap
  (`CreateWatch`, `Conflict` on exceeding) and API rate limits.
  Revisit if verification states gain tenant attribution.
- **Step 6 — Supply chain.** `.github/dependabot.yml` (gradle + actions,
  weekly); detekt via `io.gitlab.arturbosch.detekt` on backend modules,
  wired into `check`, baseline-friendly, run in CI.
- **Step 7 — Backup/DR + incident runbook.** `backend/scripts/backup.sh`
  (`pg_dump` custom format, retention), `backend/scripts/restore.sh`
  with verification query; compose `pg_backup` service (cron-style);
  `INCIDENT_RESPONSE.md` (severity ladder, roles, comms, runbooks for
  DB failure / source abuse / credential leak); alerting thresholds in
  OBSERVABILITY.md.
- **Step 8 — Secret redaction proof.** Test that logs never contain
  password/refresh-token values; `.env.example` audit note.
- **Close-out.** README Phase 14 entry; SECURITY.md §11 status;
  DEPLOYMENT.md §7 status; OBSERVABILITY.md metrics status; full
  `./gradlew build` (backend) + Android build.

## Rules honored

- Rule 4/5/6: replay + corruption tests prove idempotency/no-data-loss;
  quotas are explicit bounds.
- Rule 10: all additions are free/OSS (Micrometer, detekt, pg_dump).
- Doc updates happen in the same change as contract changes.
