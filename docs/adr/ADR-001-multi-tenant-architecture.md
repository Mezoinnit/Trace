# ADR-001: Multi-Tenant Architecture

**Status:** Accepted  
**Date:** 2026-08-21

## Context

The product is cloud-based and must support future teams/workspaces. User-owned
resources (sources, watches, alerts, notifications) must be isolated per tenant.
Ten thousand users may watch the same GitHub releases endpoint; the platform
should ingest once and fan out, not perform ten thousand identical fetches.

## Decision

- The **Workspace** is the tenant boundary. Every tenant-owned database record
  carries a `workspace_id`.
- Intelligence (sources, entities, evidence, events) is **global**; state
  (watches, subscriptions, alerts, preferences) is **user-specific** and
  scoped to a workspace.
- One source endpoint is ingested once and its events fan out to all matching
  Watches.
- Tenant context is always resolved server-side from authenticated identity.

## Consequences

- Global intelligence is shared across tenants; this requires careful access
  control so reads are authorized without leaking tenant-specific state.
- Ingestion cost is proportional to the number of unique endpoints, not
  subscribers.
- See ADR-010 for the isolation mechanism.

References: plan §4.2, §25, §34.