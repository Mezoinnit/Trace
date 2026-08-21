# ADR-009: Adaptive Polling

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Polling every source at a fixed interval wastes requests on low-frequency
sources and misses high-frequency ones. Synchronized polling spikes can
overwhelm providers. Sources fail and get rate-limited; the scheduler must react.

## Decision

- Per-endpoint adaptive intervals: `next_poll_at`, `current_interval`,
  `minimum_interval`, `maximum_interval`, `change_frequency`,
  `failure_count`, `rate_limit_state`.
- High-frequency sources poll more often; low-frequency less often.
- Repeated failures apply **exponential backoff with jitter**.
- Rate-limited sources respect provider reset timestamps.
- Webhook-capable sources prefer event-driven updates.
- The scheduler runs with jitter to prevent synchronized spikes and enforces
  concurrency limits.
- All polling decisions are recorded on `source_health` (observable and
  auditable).

## Consequences

- Request volume tracks source change frequency.
- Healthy source operation is visible (last success/failure, next poll).
- Complexity lives in the scheduler module; adapters expose capabilities the
  scheduler consumes.

References: plan §10, §47, OBSERVABILITY.md.