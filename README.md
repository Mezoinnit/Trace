# Trace — Targeted Intelligence Platform

A user-controlled intelligence monitoring platform.

**This is not a news tracker.** Not a news aggregator, not an RSS reader on
steroids, not another firehose of headlines nobody asked for. News trackers
shout everything at everyone; Trace watches **exact sources** for **exact
things** you chose, converts raw information into structured **events** and
**entities**, preserves the **evidence** and **history**, and notifies you
when it matters — and only when it matters.

> An article is **evidence** for an **event**. Framing it otherwise is how you
> end up with 400 unread "breaking" alerts about a release you don't use.

The first product domain is **Developer Intelligence**. The long-term product is a
general-purpose intelligence platform spanning companies, markets, security,
research, science, politics, and custom domains.

## Core lifecycle

```text
SOURCE → EVIDENCE → EVENT → ENTITY → INTELLIGENCE → WATCH → ALERT
```

## Repository layout

```text
android/    Android client (Kotlin + Jetpack Compose)
backend/    Backend modular monolith (Kotlin + Ktor)
docs/       ADRs and architecture documentation
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
| [docs/adr](docs/adr/) | Architecture Decision Records |
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

## Non-goals for V1

No social features, no trading, no comments/likes/followers, no microservices,
no Kubernetes, no mandatory paid AI, no mandatory paid news APIs.
