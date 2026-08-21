# ADR-003: PostgreSQL as Source of Truth

**Status:** Accepted  
**Date:** 2026-08-21

## Context

V1 avoids introducing a search engine, queue, vector store, and graph database
as separate services. The platform needs durable, transactional, searchable
storage with strict integrity guarantees (foreign keys, unique constraints,
row-level security).

## Decision

- **PostgreSQL is the single source of truth** for all state: tenants, sources,
  evidence, events, entities, watches, alerts, notifications, jobs, audit log.
- Search via PostgreSQL FTS + `pg_trgm` (ADR-013).
- Job queue as a Postgres table (ADR-008).
- Vector similarity via `pgvector` only when justified.
- Schema changes are versioned Flyway migrations, forward-only and never
  destructive.

## Consequences

- One database to operate and back up; transactional integrity for multi-row
  pipeline steps.
- Requires disciplined query design (indexes, cursor pagination, EXPLAIN
  ANALYZE) to meet performance requirements.
- No new infrastructure services until actual scale justifies them (avoiding
  Elasticsearch/Redis/Kafka in V1).

References: plan §21, §24, §28, §46.