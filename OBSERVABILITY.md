# Trace — Observability

> Phase 0 contract.
>
> **Phase status (Phase 14 — implemented):** structured logs, request IDs,
> audit records, health endpoints, source-health tracking **and** counters/
> metrics are live. Micrometer-backed `MetricsPort` (vendor-neutral port, Rule
> 9-style replaceability) exposes `fetch_total{outcome=success|failure|
> rate_limited|network_failed|parser_failed|blocked}`,
> `evidence_created_total`, `evidence_versioned_total`, `events_created_total`,
> `alerts_created_total` and the `job_queue_depth` / `jobs_dead_lettered`
> gauges; scrape them at `GET /metrics`
> (Prometheus text format). Logs, metrics, tracing, and audit must exist before any
> pipeline stage is shipped (Definition of Done).

## 1. Logging

Structured logs. Common fields:

```text
timestamp, level, service, environment, correlation_id, request_id,
workspace_id (when safe), source_id (when safe), event_id (when safe),
operation, latency, result
```

Never log: access tokens, refresh tokens, API keys, passwords, raw credentials,
or private content unnecessarily. Sensitive fields are redacted.

Correlation IDs thread the whole chain:

```text
source fetch → evidence → event → watch match → alert → notification
```

so an operator can answer: *"Why did this user receive this notification?"*

## 2. Metrics

### Sources

```text
fetch_success_rate, fetch_failure_rate, fetch_latency,
parser_success_rate, source_freshness, rate_limit_events
```

### Intelligence

```text
evidence_created, event_created, event_merged, duplicate_rate,
contradiction_rate, entity_resolution_confidence
```

### Watches / alerts

```text
watch_matches, alerts_created, false_positive_feedback
```

### Notifications

```text
notification_queued, notification_sent, notification_failed, notification_opened
```

### AI

```text
requests, latency, failures, tokens, provider, cost (if applicable)
```

### Jobs

```text
jobs_enqueued, jobs_completed, jobs_failed, dead_letters, queue_depth, lease_age
```

## 3. Health endpoints

- `/health` — liveness.
- `/health/ready` — readiness including database connectivity.

## 4. Source health

Per-endpoint state machine surfaced to users and operators
(SOURCE_ADAPTERS.md, DATA_MODEL.md `source_health`). Degraded sources are
never hidden.

## 5. Audit records

Important state changes create `audit_log` rows:

```text
Watch created / changed / deleted
Source added / disabled
Credential changed
Notification preference changed
Event created / merged / corrected / status changed
```

Historical intelligence is never silently mutated; corrections are versioned
(Rule 5).

## 6. Tracing

Every processing chain is traceable via `correlation_id`. An investigation of
"why did this notification fire" returns the exact chain of decisions
(relevance, significance, confidence, watch match, alert, delivery).

## 7. Backpressure signal

Queue depth and worker saturation are monitored; ingestion must not overwhelm
the database, AI providers, external APIs, or notification services.