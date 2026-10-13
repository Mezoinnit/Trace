# ADR-017: Tenant Isolation Enforcement Strategy

**Status:** Accepted
**Date:** 2026-09-15

## Context

ADR-010 requires PostgreSQL Row-Level Security as database defense-in-depth
"where the deployment model supports it" and deferred the decision to Phase 14.
The master plan (§25) likewise recommends RLS *preferentially*, not absolutely.

A GUC-based RLS design (`current_setting('trace.workspace_id')`) was evaluated
against the actual access patterns of the implemented system:

1. **Global shared intelligence (ADR-001).** `sources`, `source_endpoints`,
   `source_health`, `evidence`, and `events` are deliberately global: one
   ingestion fans out to every watching workspace. Most rows have no tenant
   column at all.
2. **Cross-tenant fan-out is required for correctness.** The watch engine
   (`MatchWatchesForEvent`) reads ACTIVE watches **across all workspaces** to
   match a new event, and the worker then creates alerts for many different
   workspaces inside a single transaction (Rule 4 idempotency). A policy keyed
   on one per-transaction workspace would break exactly this fan-out, or force
   `NULL`-GUC escape hatches (policies that silently pass when the GUC is
   unset) which are pseudo-isolation: one forgotten `set_config` on a pooled
   connection and every query bypasses the policy invisibly.
3. **Single database identity.** All deployables (api, worker, scheduler) and
   tests connect with one role over one pool. There is no untrusted role to
   which policies could meaningfully apply; the app owns the tables and table
   owners bypass RLS unless `FORCE`d, and `FORCE` also breaks the migrations
   and superuser bootstrap paths.
4. **Coroutine-based worker.** Transactions are `ThreadLocal`-scoped
   (`TxRunnerImpl`); setting connection-scoped GUCs per tenant inside
   `Dispatchers.IO` coroutines adds cross-cutting state for zero net gain
   given point 2.

## Decision

- **Application-layer enforcement remains the isolation boundary** and is
  proven by the Phase 14 mandatory test matrix (`CrossTenantResourceIsolationITest`,
  `TenantIsolationApiTest`): every tenant-owned read/mutate path returns 404
  for non-members and never leaks another tenant's data.
- **RLS is not enabled** on the current single-role, single-database deployment
  because no policy can be both correct (preserve global feed + cross-tenant
  fan-out) and actually restrictive. Enabling fake policies would violate the
  intent of ADR-010 while appearing to satisfy it.
- **Revisit trigger:** RLS becomes mandatory the moment the deployment model
  changes to per-tenant database roles, separate pools per trust boundary, or
  storage-level tenant separation (e.g. schema-per-tenant). At that point the
  fan-out join moves behind a `SECURITY DEFINER` function that asserts the
  caller's membership explicitly.

## Consequences

- Isolation strength rests on the application layer + review + tests, exactly
  as ADR-010's primary mechanism. ADR-010's "where the deployment model
  supports it" clause is resolved as **not supported by the current model**,
  with the revisit condition documented here.
- New tenant-owned tables must keep the `workspace_id NOT NULL + index`
  convention so a later RLS enablement is a mechanical migration.

References: ADR-010, plan §25, plan Phase 14, THREAT_MODEL.md T1.
