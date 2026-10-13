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

# Or run everything (API + worker + scheduler) in one process:
EMBED_WORKER=true ./gradlew :server:api:run
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
| `EMBED_WORKER` | unset | `true` runs worker + scheduler inside the API process (§5.1) |
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

### 5.1 All-in-one mode (single free-tier instance)

For single-user deployments where only one process slot is available (e.g.
Render free tier), the API process can host the worker and scheduler loops:

```bash
EMBED_WORKER=true ./gradlew :server:api:run
```

When `EMBED_WORKER=true`, the API JVM runs `JobLoop` and `ScheduleDueFetches`
in-process (same container, same DataSource; DB leases still make double
scheduling safe). The standalone units above remain the reference deployment
for horizontal scaling — all-in-one mode is a deployment option, not a change
to the architecture.

#### One-time setup checklist (Render free tier)

1. Push this repository to GitHub (Render deploys from a git remote).
2. Create a Postgres: Neon.tech free tier (does not expire) or a Render free
   Postgres (expires after 30 days — recreate and restore from backup, §7).
   Copy its connection string and append `?sslmode=require` for Neon.
3. Render dashboard → **New +** → **Blueprint** → select the repo. Render
   reads `render.yaml`; fill in the synced values: `DATABASE_URL`,
   `DATABASE_USER`, `DATABASE_PASSWORD`, and a generated `JWT_SECRET`
   (`openssl rand -base64 48`, ≥32 bytes — ADR-015).
4. Wait for the first build (~5–10 min: Gradle downloads dependencies inside
   the image). Verify the service health check: `GET /health/ready`.
5. Build the app against the public URL:
   `./gradlew :app:assembleRelease -PtraceBaseUrl=https://<service>.onrender.com`
   (any URL containing `://` is used verbatim; HTTPS is required for cleartext
   policy reasons).
6. Install the APK and register — data now lives in the cloud database.

Free-tier caveats: Render free services sleep after ~15 min idle (first
request after a wake adds ~30–60 s latency; scheduled fetches pause while
asleep) and free Postgres instances expire on Render (use Neon to avoid
this, or restore `backend/scripts/backup.sh` output after recreation).

## 6. CI pipeline

`.github/workflows/ci.yml`:

1. Backend: `./gradlew build` (compile + tests) on a fresh Postgres.
2. Android: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
3. Migrations are validated by running Flyway against the CI Postgres.
4. Failures block merge.

## 7. Backups and DR (Phase 14 — implemented)

- Postgres: `backend/scripts/backup.sh` performs `pg_dump` in custom format
  with configurable retention; `backend/scripts/restore.sh` restores and runs
  a verification query before reporting success. The compose file ships a
  `pg_backup` service that runs the same script on an interval loop
  (`restart: unless-stopped`) so drills run without extra tooling.
- Object storage: versioned, cross-region copy where the provider supports it.
- Incident procedures: `INCIDENT_RESPONSE.md` (severity ladder, roles,
  runbooks for database failure, source abuse, credential leak).
- Monitoring/alerting: Prometheus-format `/metrics` (fetch/evidence/events/
  alerts counters, `job_queue_depth` and `jobs_dead_lettered` gauges) plus
  health endpoints; alert thresholds in OBSERVABILITY.md.

## 8. Secrets in deployment

- Environment-managed or encrypted secret store; rotation; scoped credentials.
- AI provider keys and FCM credentials exist only server-side. The Android app
  holds no credentials and talks only to the backend.