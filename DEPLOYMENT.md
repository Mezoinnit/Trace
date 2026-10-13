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
   Postgres (expires after 30 days — recreate and restore from backup, §8).
   Use Neon's **direct** endpoint (not the `-pooler` pooled one) and convert
   its URL to JDBC form — e.g. `postgresql://user:pass@ep-abc.aws.neon.tech/neondb`
   becomes `DATABASE_URL=jdbc:postgresql://ep-abc.aws.neon.tech:5432/neondb?sslmode=require`,
   with the user/password in `DATABASE_USER`/`DATABASE_PASSWORD` instead of
   the URL.
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

## 6. Bare-metal VPS — Oracle Cloud Always Free (no Docker)

An Always Free Oracle Cloud VM (Ampere A1: up to 4 OCPUs + 24 GB RAM,
unlimited duration, never sleeps) runs Trace as plain systemd services —
no containers anywhere. The all-in-one mode (§5.1) is the deployment shape;
`backend/deploy/` ships everything the server needs. Postgres runs on the
same VM, bound to localhost.

### 6.1 Oracle Cloud console (first time)

1. Sign up at <https://cloud.oracle.com/free> (one free account per person;
   a card is verified but not charged; the account stays **Always Free** after
   the trial if you stay within limits).
2. **Home → Compute → Create Instance**:
   - Name: `trace-vm`.
   - Image: **Ubuntu 24.04 (aarch64)**.
   - Shape: **VM.Standard.A1.Flex**, 2 OCPUs / 4 GB RAM (Always Free allows
     up to 4 OCPUs / 24 GB total — stay at or under that).
   - SSH keys: paste your public key (`cat ~/.ssh/id_ed25519.pub`).
3. Networking: during creation choose **"Create new virtual cloud network"**
   with defaults (public subnet). After creation, open
   **Networking → Virtual Cloud Networks → your VCN → Default Security List**
   → add **ingress rules**: TCP 22 (ideally restricted to your IP), TCP 80,
   TCP 443. Nothing else — Postgres is never exposed.
4. Note the instance's **public IP**.
   - Capacity note: A1 shapes sometimes return "out of capacity" — pick
     another region (Ashburn, Phoenix, Tokyo commonly work) and retry.
   - Hygiene: log into the console every few weeks — Oracle may flag accounts
     idle 30+ days as abandoned.

### 6.2 DuckDNS hostname (free)

1. Create a free account at <https://www.duckdns.org>, add a subdomain
   (e.g. `trace-yourname`), and point its **A record** at the VM's public IP.
2. The Android app requires HTTPS, so this hostname is what the app will call;
   Caddy below provisions a real Let's Encrypt certificate for it automatically.

### 6.3 Server setup (one command)

SSH in as the image's default user and run the shipped installer — it is
idempotent (safe to re-run) and keeps all secrets on the machine:

```bash
ssh ubuntu@<vm-public-ip>
git clone https://github.com/Mezoinnit/Trace.git
cd Trace/backend/deploy
sudo ./setup.sh trace-yourname.duckdns.org   # your DuckDNS hostname
```

`setup.sh` installs OpenJDK 17, PostgreSQL 16, Caddy and ufw; creates the
`trace` system user and database; generates `JWT_SECRET` and the DB password
into `/etc/trace/env` (mode 600, root-owned, never committed); builds
`:server:api:installDist`; and enables `trace.service`,
`trace-backup.timer` and Caddy (HTTPS termination on :443 → API on :8080).

### 6.4 Verify

```bash
curl https://trace-yourname.duckdns.org/health/ready
journalctl -u trace -n 20          # app log
systemctl status trace trace-backup.timer caddy
```

### 6.5 Android client

```bash
cd android
./gradlew :app:assembleDebug -PtraceBaseUrl=https://trace-yourname.duckdns.org
```

Install the APK on the device and register — data lives in the VM's Postgres.

### 6.6 Day-2 operations

- **Update after pushing to GitHub**: `sudo /opt/trace/backend/deploy/deploy.sh`
  (pull → rebuild → restart → health check).
- **Backups**: `trace-backup.timer` runs `backup.sh` daily into
  `/var/backups/trace` (14 retained). Copy dumps off the VM regularly —
  a backup on the same disk is not DR. Restore drill: §8 + `restore.sh`.
- **Secrets**: `/etc/trace/env` only (Rule: never commit, never log).
- **Metrics**: `https://<hostname>/metrics` (Prometheus text format; consider
  restricting to your IP in the Caddyfile).

## 7. CI pipeline

`.github/workflows/ci.yml`:

1. Backend: `./gradlew build` (compile + tests) on a fresh Postgres.
2. Android: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
3. Migrations are validated by running Flyway against the CI Postgres.
4. Failures block merge.

## 8. Backups and DR (Phase 14 — implemented)

- Postgres: `backend/scripts/backup.sh` performs `pg_dump` in custom format
  with configurable retention; `backend/scripts/restore.sh` restores and runs
  a verification query before reporting success. The compose file ships a
  `pg_backup` service that runs the same script on an interval loop
  (`restart: unless-stopped`); bare-metal deployments get the same via the
  `trace-backup.timer` systemd unit (§6.3). Drills run without extra tooling.
- Object storage: versioned, cross-region copy where the provider supports it.
- Incident procedures: `INCIDENT_RESPONSE.md` (severity ladder, roles,
  runbooks for database failure, source abuse, credential leak).
- Monitoring/alerting: Prometheus-format `/metrics` (fetch/evidence/events/
  alerts counters, `job_queue_depth` and `jobs_dead_lettered` gauges) plus
  health endpoints; alert thresholds in OBSERVABILITY.md.

## 9. Secrets in deployment

- Environment-managed or encrypted secret store; rotation; scoped credentials.
- Bare-metal (§6): secrets live only in `/etc/trace/env` (mode 600).
- AI provider keys and FCM credentials exist only server-side. The Android app
  holds no credentials and talks only to the backend.