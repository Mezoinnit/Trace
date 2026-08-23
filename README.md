# Trace — Targeted Intelligence Platform

A user-controlled intelligence monitoring platform.

Users monitor **exact sources** for **exact things**, the platform converts raw
information into structured **events** and **entities**, preserves the **evidence**
and **history**, and notifies the user when it matters.

> This is not a news aggregator. An article is **evidence** for an **event**.

The first product domain is **Developer Intelligence**. The long-term product is a
general-purpose intelligence platform spanning companies, markets, security,
research, science, politics, and custom domains.

The authoritative product and implementation plan lives in
[`docs/targeted_intelligence_platform_master_plan.md`](docs/targeted_intelligence_platform_master_plan.md).

## Core lifecycle

```text
SOURCE → EVIDENCE → EVENT → ENTITY → INTELLIGENCE → WATCH → ALERT
```

## Repository layout

```text
android/    Android client (Kotlin + Jetpack Compose)  — Phase 9
backend/    Backend modular monolith (Kotlin + Ktor)    — Phase 1+
docs/       Master plan + ADRs
```

Root documentation:

| File | Purpose |
|------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture, modules, deployable units |
| [DATA_MODEL.md](DATA_MODEL.md) | PostgreSQL schema / ERD for all core entities |
| [API.md](API.md) | Versioned REST API contract (`/api/v1`) |
| [SOURCE_ADAPTERS.md](SOURCE_ADAPTERS.md) | Source adapter contract and implementations |
| [EVENT_MODEL.md](EVENT_MODEL.md) | Event-centric domain model |
| [WATCH_MODEL.md](WATCH_MODEL.md) | Watch matching and alert model |
| [AI.md](AI.md) | AI gateway abstraction and usage rules |
| [SECURITY.md](SECURITY.md) | Security architecture and controls |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Threat model and mitigations |
| [OBSERVABILITY.md](OBSERVABILITY.md) | Logging, metrics, tracing, audit |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Deployment, migrations, environments |
| [COST_MODEL.md](COST_MODEL.md) | Cost rules and budget model |
| [docs/adr](docs/adr/) | Architecture Decision Records (14 required) |
| [AGENTS.md](AGENTS.md) | Engineering rules for the implementing AI |

## Development

Prerequisites:

- JDK 17+
- Docker (for local PostgreSQL)
- Android SDK (for the Android client)

### Backend

```bash
cd backend
cp .env.example .env          # adjust as needed
docker compose up -d          # PostgreSQL
./gradlew build               # compile + test
./gradlew :server:api:run     # HTTP API on :8080
```

Health check:

```bash
curl http://localhost:8080/health
# {"status":"ok","service":"api","version":"0.1.0"}
```

### Android

```bash
cd android
./gradlew :app:assembleDebug
```

## Implementation order

Phases 0–19 as defined in the master plan. Phases are executed in order and are
not considered complete until the Definition of Done in the plan is satisfied.
Current status:

- **Phase 0 — completed:** repo restructured into `backend/` + `android/`,
  15 root docs, 14 ADRs, backend modular-monolith scaffold, CI pipeline.
- **Phase 1 — completed:** multi-tenant backend core. JWT auth (argon2id +
  rotating refresh tokens with reuse detection), workspaces + membership,
  tenant-isolated repository layer with explicit transaction boundaries,
  `/api/v1` error envelope, request IDs, rate limiting, `/health/ready`,
  structured JSON logging option, 46 automated tests including the
  tenant-isolation matrix (embedded Postgres). See ADR-015.
- **Phase 2 — completed:** source system. SSRF-hardened fetcher (IP-validated
  hops, redirect revalidation, size caps), adapter contract with GitHub/RSS/
  Atom adapters plus a degraded web fallback, URL→endpoint resolver with
  feed discovery, global source registry with per-endpoint health tracking,
  `/api/v1/sources` routes. XXE-safe XML parsing.
- **Phase 3 — completed:** evidence system. Immutable evidence keyed by
  `(endpoint_id, external_id)` so repeated fetches never duplicate;
  content changes append `evidence_versions` (history preserved, ADR-006);
  full provenance (source, endpoint, external id, canonical URL, timestamps)
  on every record; fetch pipeline persists normalized items automatically.
- **Phase 4 — completed:** GitHub end-to-end vertical slice. Deterministic
  SOFTWARE_RELEASE events from release evidence (replay-safe via unique
  `external_ref`), watches bound to exact endpoints, exactly-one alerts per
  `(watch_id, event_id)` (ADR-012), cursor-paginated `/api/v1/events` feed,
  watch CRUD with server-side membership checks. Proven by integration tests:
  new releases alert exactly once per watching workspace; replays never
  duplicate; one ingestion fans out to multiple watchers.
- **Phase 5 — completed:** event engine. Multi-signal clustering (version-gate
  + title similarity + temporal window, source-scoped), inspectable confidence
  scores with recorded reasoning inputs, corroboration across distinct
  endpoints, and CONTESTED state preserving contradictory evidence.
- **Phase 6 — completed:** entity system. Deterministic resolution by
  structured identifiers (`GITHUB_REPO:owner/repo`) with alias backfill —
  never name-only; repository entities linked to their release events;
  `/api/v1/entities/{id}` with recent events. Distinct repositories never
  merge.
- **Audit remediation (post-Phase 6):** entity merge with recovery pointers,
  watch GET/PATCH + optimistic locking + lifecycle audit, account-deletion
  tests, bounded pagination backfill, shared adapter contract suite
  (114 tests).
- Phases 7+ — see the plan (watch engine: full rule set next).

## Non-goals for V1

No social features, no trading, no comments/likes/followers, no microservices,
no Kubernetes, no mandatory paid AI, no mandatory paid news APIs.