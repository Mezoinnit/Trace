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

- **Phase 0 — completed** (pending human review): repo restructured into
  `backend/` + `android/`, 15 root docs, 14 ADRs, backend modular-monolith
  scaffold (builds + tests pass), CI pipeline, git initialized.
- Phases 1+ — see the plan.

## Non-goals for V1

No social features, no trading, no comments/likes/followers, no microservices,
no Kubernetes, no mandatory paid AI, no mandatory paid news APIs.