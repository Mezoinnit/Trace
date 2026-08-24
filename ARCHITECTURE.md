# Trace — System Architecture

> Status: Phase 0 — frozen before feature implementation. Deviations require an
> ADR (see `docs/adr/`).

## 1. Overview

Trace is a **modular monolith with asynchronous workers**. The backend is a
single codebase with strict internal module boundaries that can later be split
into independent services when actual scale justifies it (ADR-002).

The system is **event-centric**: many pieces of **evidence** (articles, releases,
documents, third-party reports) resolve into a single **event** referencing
**entities** and **sources**. Users create **Watches**; events matching a Watch
produce **Alerts** and **Notifications**.

```text
SOURCE
  ↓
EVIDENCE   (immutable, versioned, traceable)
  ↓
EVENT      (deduplicated, clustered, confident, significant)
  ↓
ENTITY     (resolved identity layer)
  ↓
WATCH      (persistent monitoring intent)
  ↓
ALERT      (idempotent event↔watch match)
  ↓
NOTIFICATION (FCM / in-app)
```

## 2. Principles

1. Deterministic processing first; AI is an assistive layer only (AI.md).
2. Every tenant-owned record carries a `workspace_id` (ADR-001, ADR-010).
3. Every retryable operation is idempotent (ADR-004, ADR-012).
4. No unbounded operations: every fetch, parse, loop, and query is bounded.
5. Fail partially, never globally (graceful degradation).
6. PostgreSQL is the source of truth (ADR-003). No separate search/queue
   services in V1.
7. Sources are monitored as **exact endpoints**, never silently broadened
   (ADR-005).

## 3. System context

```text
                 ANDROID CLIENT
                      │  HTTPS /api/v1
                      ▼
             ┌──────────────────┐
             │    API (Ktor)    │
             └────────┬─────────┘
                      │
      ┌───────────────┼────────────────┐
      ▼               ▼                ▼
  SCHEDULER      WORKER(S)         (future: services)
      │               │
      └──────┬────────┴───┐
             ▼            ▼
        POSTGRESQL    OBJECT STORAGE
        (source of truth,  (raw/large payloads
         FTS + pg_trgm +    when required)
         pgvector)
             │
             ▼
      EXTERNAL SOURCES (GitHub, RSS, Atom, REST, Web …)
```

Deployable units (ADR-002):

- **API** (`server:api`) — HTTP API + auth, `/api/v1`, health checks.
- **Worker** (`server:worker`) — consumes durable jobs from the Postgres job
  queue (ADR-008).
- **Scheduler** (`server:scheduler`) — computes adaptive `next_poll_at` with
  jitter and enqueues fetch jobs (ADR-009).

All three share the same codebase and domain modules.

## 4. Backend module structure

Gradle modules (dependency direction `server → infrastructure → application →
domain`):

```text
backend/
├── core/domain/            # Pure domain models + logic. NO dependencies.
├── core/application/       # Use cases / application services.
├── infrastructure/
│   ├── persistence/        # PostgreSQL, repositories, job queue, Flyway migrations.
│   └── (future) fetch/     # SSRF-safe HTTP fetcher.
│   └── (future) notifications/  # FCM dispatcher.
├── adapters/               # (future) github/, rss/, atom/, generic/…
├── ai/                     # (future) AiGateway + providers (LocalProvider first).
└── server/
    ├── api/                # Deployable: HTTP API.
    ├── worker/             # Deployable: async worker.
    └── scheduler/          # Deployable: adaptive scheduler.
```

`domain` must not import Ktor, JDBC, HTTP clients, or AI SDKs.

## 5. Logical modules (packages)

Business capabilities map to packages inside the application layer:

```text
auth            tenancy         sources          ingestion
evidence        entities        events           intelligence
watches         alerts          notifications    search
ai              scheduler       market (future)  observability
```

## 6. Event processing pipeline

Every stage is observable and bounded (OBSERVABILITY.md):

```text
FETCH → VALIDATE → NORMALIZE → DEDUP RAW EVIDENCE → CLASSIFY →
EXTRACT ENTITIES → EXTRACT EVENT → RESOLVE ENTITIES →
CLUSTER/DEDUP EVENTS → CORROBORATE → DETECT CONTRADICTIONS →
CALCULATE CONFIDENCE → CALCULATE SIGNIFICANCE → INDEX →
MATCH WATCHES → CREATE ALERTS → SEND NOTIFICATIONS
```

Stages are driven by durable jobs (ADR-008) so retries, backoff, and dead-letter
behavior are uniform.

## 7. Job system

PostgreSQL-backed durable queue. One table, typed payloads, concurrency bounds.

Fields: `id, type, payload, priority, status, attempts, max_attempts,
available_at, locked_at, locked_by, completed_at, failed_at, error_code,
error_message, created_at`.

Job types (V1): `FETCH_SOURCE, PARSE_SOURCE, NORMALIZE_EVIDENCE,
CLASSIFY_EVENT, RESOLVE_ENTITY, MERGE_EVENT, MATCH_WATCHES, CREATE_ALERT,
SEND_NOTIFICATION, REINDEX_EVENT, GENERATE_SUMMARY`.

## 8. Sources

- A **Source** is a logical information source; a **Source Endpoint** is the
  exact monitored resource (e.g. `github://androidx/androidx/releases`).
- Adapters implement a stable contract (SOURCE_ADAPTERS.md).
- Source health is tracked and surfaced to users (Rule 7). Never silently stop
  monitoring.
- Adaptive polling with jitter, backoff, and rate-limit awareness (ADR-009).

## 9. AI layer

- All AI access goes through `AiGateway` (AI.md). Domain never imports provider
  SDKs.
- AI outputs are untrusted structured input, validated against schemas.
- The system must operate with AI unavailable.
- V1 uses deterministic processing; a local provider is preferred for
  development (ADR-007).

## 10. Data layer

- PostgreSQL: single source of truth (ADR-003). Cursor pagination everywhere.
- Search: PostgreSQL FTS + `pg_trgm` (ADR-013). `pgvector` when semantic
  retrieval is justified.
- Object storage: S3-compatible for raw/large evidence payloads when scale
  requires it.
- Migrations: Flyway, forward-only, never destructive (DEPLOYMENT.md).

## 11. Tenant isolation

- `workspace_id` on every tenant-owned table.
- Tenant context resolved server-side from the authenticated identity, never
  from client input.
- Application-level authorization is mandatory; Row-Level Security is the
  database defense-in-depth layer where supported (ADR-010).
- Every repository/query method enforces tenant context.

## 12. Android architecture

Kotlin + Jetpack Compose, Clean Architecture, MVVM, Coroutines + Flow.

**Implemented structure (ADR-016):** single `:app` module whose packages
mirror the planned modules — `core/network` (Ktor client per API.md),
`core/data` (repositories + DataStore session), `feature/{home,sources,
watches,alerts,events,auth}`, `ui/components`. Manual DI via `TraceApp`
composition root; Hilt and Gradle module split deferred until scale
justifies them.

The backend remains the source of truth; Room is used only for client-side
cache/offline state. The UI distinguishes `cached / syncing / current /
offline` and never implies cached intelligence is real-time.

## 13. Shared intelligence

Global intelligence (sources, entities, evidence, events) is separated from
user-specific state (watches, subscriptions, alerts, notification preferences).
One source is ingested once and fans out to all matching Watches. Do not perform
N identical fetches for N subscribers (ADR-001).

## 14. Graceful degradation

- AI down → deterministic pipeline still ingests, stores, and notifies.
- One source down → others continue; source marked degraded.
- Search down → core feeds and Watches continue.
- FCM down → alerts remain queued and visible in-app.
- Generic parser broken → that source is degraded and the user is informed.

## 15. Concurrency and backpressure

- Bounded concurrency at every layer (jobs, fetches, AI calls, notifications).
- Queue depth monitoring; workers never overwhelm the database, providers, or
  notification services.

## 16. Observability

Structured logs with `correlation_id`/`request_id`, metrics (fetch success,
evidence/event created, alerts, notifications, AI requests), tracing of
`source → evidence → event → watch → alert → notification`, and audit records
for important state changes. See OBSERVABILITY.md.

## 17. Technology choices

| Concern | Choice | Rationale (ADR) |
|---------|--------|------------------|
| Language | Kotlin | shared with Android |
| Framework | Ktor | lightweight, coroutine-native |
| Database | PostgreSQL | single source of truth (ADR-003) |
| Search | Postgres FTS + pg_trgm | no new service in V1 (ADR-013) |
| Vector | pgvector | only when justified |
| Queue | Postgres job table | no new service in V1 (ADR-008) |
| Push | FCM | Android notifications |
| Object storage | S3-compatible | raw/large payloads when required |
| Infra | Docker Compose (dev) | $0 development |

Explicitly avoided until scale justifies: Kubernetes, Kafka, Elasticsearch,
Redis, dedicated graph databases, microservices.