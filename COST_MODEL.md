# Trace — Cost Model

> Phase 0 contract. Design for $0 development and minimal cost at early
> production scale (Rule 10, plan §19).

## 1. Budget rules

1. Prefer deterministic parsing over AI.
2. Prefer provider APIs over scraping where available.
3. Use webhooks when available; conditional HTTP (ETag / Last-Modified) when not.
4. Cache aggressively where valid; deduplicate before expensive processing.
5. Never embed every document; store compact evidence + canonical links.
6. Do not invoke LLMs for known structured events.
7. Batch compatible processing; apply per-tenant and per-source quotas.
8. Respect provider rate limits; use backpressure.
9. Store raw payloads in object storage only when scale requires it.
10. AI providers are **optional configuration**, never a hard dependency.

## 2. What is free (V1)

| component | cost | notes |
|-----------|------|-------|
| PostgreSQL | $0 | Docker locally; FOSS hosting/managed at own infra |
| Ktor / Kotlin | $0 | open source |
| GitHub API (unauthenticated) | $0 | rate-limited; see §4 |
| RSS/Atom ingestion | $0 | |
| Local AI provider | $0 | `LocalProvider` for dev/self-host (ADR-007) |
| FCM | $0 | Firebase push |
| Search | $0 | Postgres FTS + pg_trgm (ADR-013) |

## 3. What is optional (later, documented in an ADR)

- Paid AI providers (Gemini/OpenAI/Anthropic) — configurable, quota-bounded.
- Object storage — S3-compatible, only for raw payloads at scale.
- Managed Postgres — infrastructure choice, not a product dependency.

## 4. GitHub cost control (Phase 4)

- Prefer conditional polling (ETag/If-None-Match) to avoid re-downloading.
- Respect the unauthenticated rate limit; back off on `RATE_LIMITED`.
- Ingest each source once and fan out to all matching Watches (ADR-001).

## 5. AI cost discipline

- Per-operation token caps (Rule 6).
- Cache deterministic AI results (`ai_operations.cache_hit`).
- Never send every piece of evidence to an LLM.
- Per-tenant and per-workspace monthly AI budgets with hard stops.

## 6. Infrastructure efficiency

- Adaptive polling with jitter (ADR-009) keeps request volume proportional to
  source change frequency.
- Cursor pagination and indexed queries prevent unbounded scans.
- One ingestion, N Watch consumers — never N identical fetches.

## 7. Monitoring spend

Metrics track fetch volume, AI tokens, storage growth, and job throughput
(OBSERVABILITY.md). A sustained cost anomaly is a product bug, not a bill.