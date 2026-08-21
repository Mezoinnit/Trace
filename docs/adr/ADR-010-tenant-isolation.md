# ADR-010: Tenant Isolation

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Multi-tenant correctness is a hard requirement. A compromised or buggy
endpoint must not expose another tenant's data. The client must never be able
to assert which workspace it belongs to.

## Decision

- Every tenant-owned table has a `workspace_id` column, indexed, `NOT NULL`.
- The active `workspace_id` is **resolved server-side from the authenticated
  identity**; client-supplied values are ignored or rejected.
- Application-level authorization is mandatory and enforced in every repository
  and query method.
- PostgreSQL **Row-Level Security** is applied as database defense-in-depth
  where the deployment model supports it.
- Cross-tenant access attempts return `404` to avoid resource enumeration.

## Consequences

- Defense in depth: application layer + RLS.
- Every new query/repository must be written tenant-aware; enforced by review
  and security tests (IDOR, cross-tenant access) in Phase 14.

References: plan §25, §38, THREAT_MODEL.md T1.