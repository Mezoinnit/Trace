# Trace — Deployment and Environments

> Phase 0 contract. Local development works with **no paid dependency**
> (Rule 10). The architecture allows a later split of API/Worker/Scheduler into
> independent services without changing domain code (ADR-002).

## 1. Environments

| environment | database | AI | purpose |
|-------------|----------|----|---------|
| local | Docker Postgres | LocalProvider (or none) | daily development |
| CI | ephemeral Postgres | none | automated verification |
| staging | managed Postgres | LocalProvider / test keys | integration validation |
| production | managed Postgres + object storage | configured providers (optional) | real users |

## 2. Local development

```bash
cd backend
cp .env.example .env
docker compose up -d          # PostgreSQL on :5432
./gradlew build               # compile + test
./gradlew :server:api:run     # API on :8080
./gradlew :server:worker:run  # worker
./gradlew :server:scheduler:run  # adaptive poll scheduler (ADR-009)
```

Health check:

```bash
curl http://localhost:8080/health
```

Android:

```bash
cd android
./gradlew :app:assembleDebug
```

## 3. Configuration

Configuration is environment-driven. `backend/.env.example` documents every
variable. No secrets are committed.

| variable | default | notes |
|----------|---------|-------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/trace` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `trace` / `trace` | local defaults |
| `DATABASE_MAX_POOL_SIZE` | `10` | |
| `PORT` | `8080` | HTTP API port |
| `TRACE_ENV` | unset | set to `production` to enforce strict secret handling |
| `JWT_SECRET` | dev fallback (insecure) | **required in production**, ≥32 bytes (ADR-015) |
| `ACCESS_TOKEN_TTL_MINUTES` | `15` | |
| `REFRESH_TOKEN_TTL_DAYS` | `30` | |
| `RATE_LIMIT_AUTH_PER_MIN` | `10` | per client IP on `/api/v1/auth/*` |
| `RATE_LIMIT_API_PER_MIN` | `120` | per user on other `/api/v1/*` routes |
| `WORKER_CONCURRENCY` | `4` | parallel job executions per worker |
| `SCHEDULER_TICK_MS` | `5000` | scheduler scan cadence |
| `LOGBACK_CONFIGURATION_FILE` | console logging | set to `logback-prod.xml` for JSON logs |

## 4. Migrations strategy

- Versioned SQL migrations: `backend/infrastructure/persistence/src/main/
  resources/db/migration/V{n}__description.sql`.
- Applied by Flyway on worker/scheduler/API startup (idempotent).
- Forward-only. **Never destructive.** No
  `fallbackToDestructiveMigration` (or equivalents) in production.
- Migration policy:
  - additive DDL (new tables/columns/constraints) — preferred;
  - data changes are reversible where possible or explicitly documented;
  - enum additions alter `CHECK` constraints via new migrations;
  - a migration that requires a value transformation must not lose data
    (Rule 5).
- Rollbacks are handled by new forward migrations, never by editing applied
  ones (checksums must stay stable).

## 5. Deployable units

```text
server:api         HTTP API + auth            (horizontal scale)
server:worker      job consumer               (horizontal scale)
server:scheduler   adaptive poll scheduler    (singleton-safe via DB lease)
```

All units share the codebase; each ships as its own executable. Deploy with
plain container images (no Kubernetes in V1).

## 6. CI pipeline

`.github/workflows/ci.yml`:

1. Backend: `./gradlew build` (compile + tests) on a fresh Postgres.
2. Android: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
3. Migrations are validated by running Flyway against the CI Postgres.
4. Failures block merge.

## 7. Backups and DR (Phase 14)

- Postgres: scheduled logical backups; verified restore drills.
- Object storage: versioned, cross-region copy where the provider supports it.
- Incident procedures and monitoring/alerting defined before production.

## 8. Secrets in deployment

- Environment-managed or encrypted secret store; rotation; scoped credentials.
- AI provider keys and FCM credentials exist only server-side. The Android app
  holds no credentials and talks only to the backend.