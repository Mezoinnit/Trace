# ADR-008: PostgreSQL Job Queue

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Ingestion is asynchronous and must survive failures with retries, backoff, and
dead-letter state. A dedicated queue service (Redis, Kafka) is explicitly
avoided in V1 (ADR-003). The queue must support idempotency and bounded
concurrency.

## Decision

- Durable job queue as a PostgreSQL table (`jobs`).
- Fields: `id, type, payload, priority, status, attempts, max_attempts,
  available_at, locked_at, locked_by, completed_at, failed_at, error_code,
  error_message, created_at`.
- Support: retries, exponential backoff with jitter, dead-letter state, optional
  idempotency key, visibility timeout, concurrency limits.
- Job types: `FETCH_SOURCE, PARSE_SOURCE, NORMALIZE_EVIDENCE, CLASSIFY_EVENT,
  RESOLVE_ENTITY, MERGE_EVENT, MATCH_WATCHES, CREATE_ALERT, SEND_NOTIFICATION,
  REINDEX_EVENT, GENERATE_SUMMARY`.
- Workers claim jobs via `locked_at`/`locked_by` (SKIP LOCKED) and process
  within a visibility window.

## Consequences

- No extra infrastructure service; transactional guarantees with the database.
- Queue throughput is bounded by Postgres; fine at V1 scale, revisited only
  when scale justifies a dedicated broker.
- Requires careful `SKIP LOCKED` polling design and lease handling.

References: plan §22, §23, §47.