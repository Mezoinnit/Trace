# Phase 1 — Multi-Tenant Backend Core (Implementation Plan)

> Status: APPROVED by user (JWT auth + embedded Postgres + baseline commit).
> Baseline commit `5bcf537` already created. Implementation not yet started —
> plan mode was still active when implementation began.

## Goal

Secure backend foundation per master plan Phase 1.

**Exit criteria:** a user can sign in → access workspace → create/read/update/
delete tenant-owned resources, and cannot access another tenant's resources.

## Decisions (user-approved)

1. **Auth:** self-hosted JWT auth — argon2id password hashing (BouncyCastle),
   short-lived JWT access tokens (`com.auth0:java-jwt`, HS256, env secret),
   rotating refresh tokens stored hashed server-side with family-based reuse
   detection. Swap-to-OIDC path documented in ADR-015.
2. **Integration tests:** Zonky embedded-postgres (no Docker required locally;
   real Postgres semantics). Shared harness in new `test-support` module.
3. **RLS:** deferred to Phase 14; application-level tenant enforcement now.
4. **Rate limiting:** in-memory sliding window keyed by IP (auth routes) and
   userId (general API); single-instance limitation documented.

## Step 0 — DONE: baseline commit

`git commit` of all Phase 0 work as `5bcf537`.

## Step 1 — Migration `V2__auth_tenancy.sql`

Location:
`backend/infrastructure/persistence/src/main/resources/db/migration/V2__auth_tenancy.sql`

- `users` (id UUID PK, email TEXT NOT NULL lowercased-unique via
  `UNIQUE INDEX users_email_unique ON users(lower(email))`, display_name,
  password_hash, created_at/updated_at defaults now(), CHECK email=lower(email)
  and length ≤ 320)
- `workspaces` (id PK, name NOT NULL btrim<>'' ≤200, created_by → users,
  timestamps, index on created_by)
- `workspace_members` (workspace_id,user_id composite PK, role
  OWNER/ADMIN/MEMBER CHECK, ON DELETE CASCADE both FKs, index on user_id)
- `refresh_tokens` (id PK, user_id FK CASCADE, token_hash UNIQUE,
  family_id UUID, status ACTIVE/REPLACED/REVOKED, expires_at, created_at,
  rotated_at, revoked_at; indexes on user_id and family_id)
- `audit_log` (id PK, workspace_id nullable, actor_type USER/SYSTEM/JOB,
  actor_id, action, resource_type, resource_id, metadata JSONB default '{}',
  created_at; indexes (workspace_id, created_at DESC) and (action, created_at
  DESC))

## Step 2 — Domain layer (`core/domain`, no dependencies)

Package `com.mezo.trace.domain`:

- `Role` enum (OWNER, ADMIN, MEMBER)
- `UserId`, `WorkspaceRefId` value classes (reuse existing `WorkspaceId`)
- `User` data model (id, email, displayName, passwordHash, timestamps)
- `Workspace` (id, name, createdBy, timestamps)
- `WorkspaceMember` (workspaceId, userId, role, createdAt)
- `Email` value object — normalization (trim+lowercase) + format validation
- `PasswordPolicy` — pure validation: min 10 chars, max 128, must contain
  letter+digit; returns typed violations

## Step 3 — Application layer (`core/application`)

Package `com.mezo.trace.application`:

**Ports** (`port/`):
- `PasswordHasher` (hash, verify)
- `TokenIssuer` (issueAccess(userId): AccessToken, newRefreshToken(): String,
  hash(token): String)
- `ClockPort` (now(): Instant)
- `UserRepository`, `WorkspaceRepository`, `WorkspaceMembershipRepository`,
  `RefreshTokenRepository`, `AuditLogRepository`
- `TxRunner` (run { } — transaction boundary port)

**Errors** (`AppError.kt`): ValidationFailed, InvalidCredentials,
Unauthenticated, Forbidden, NotFound, Conflict(email exists), RateLimited —
mapped to API.md codes at the HTTP edge.

**Use cases** (`usecase/`):
- auth: `RegisterUser` (create user + personal workspace OWNER membership +
  audit), `SignIn` (verify, issue access+refresh, audit), `RefreshSession`
  (rotate: mark REPLACED, insert sibling same family; **reuse of
  REPLACED/REVOKED token ⇒ revoke whole family + REFRESH_TOKEN_REUSE audit +
  401**), `SignOut` (revoke family), `DeleteAccount` (delete user cascade;
  delete workspaces where sole member; audit ACCOUNT_DELETED)
- workspace: `CreateWorkspace`, `ListWorkspacesForUser`, `GetWorkspace`,
  `RenameWorkspace`, `DeleteWorkspace`, `ListMembers`
- tenancy: `RequireMembership(userId, workspaceId)` → role or NotFound
  (cross-tenant = 404 per API.md)

## Step 4 — `infrastructure/security` (NEW Gradle module)

- Depends on: `core:application` (implements ports only)
- `Argon2PasswordHasher` — BouncyCastle `Argon2BytesGenerator`, params m=19456
  KiB, t=2, p=1 (OWASP), random 16-byte salt, encoded string stored
- `JwtTokenService` — java-jwt HS256, issuer "trace", sub=user_id, exp from
  config; secret from env `JWT_SECRET` (≥32 bytes enforced at startup);
  TTLs `ACCESS_TOKEN_TTL_MINUTES` (default 15), `REFRESH_TOKEN_TTL_DAYS`
  (default 30)
- Catalog additions: `bcprov-jdk18on`, `java-jwt`

## Step 5 — Persistence (`infrastructure/persistence`)

- `TxRunnerImpl` — connection-per-transaction from Hikari, rollback on throw
- Plain JDBC repositories (no ORM), all take Connection via TxRunner context;
  every tenant-scoped query filters `workspace_id` explicitly
- `JdbcUserRepository` (findByEmail, findById, insert, delete)
- `JdbcWorkspaceRepository` (insert, findById, listForUser join members,
  rename, delete, countMembers)
- `JdbcWorkspaceMembershipRepository` (insert, findRole, listMembers, delete)
- `JdbcRefreshTokenRepository` (insert, findByHash, markReplaced, revokeFamily)
- `JdbcAuditLogRepository` (append)

## Step 6 — API (`server/api`)

- Route tree `/api/v1`: `auth/register`, `auth/signin`, `auth/refresh`,
  `auth/signout`; `me` GET/DELETE; `workspaces` GET/POST,
  `workspaces/{id}` GET/PATCH/DELETE, `workspaces/{id}/members` GET
- Auth plugin: Ktor `Authentication` jwt provider reading Bearer token →
  UserId principal; membership checks inside use cases
- `StatusPages` → API.md error envelope `{error:{code,message,request_id}}`;
  cross-tenant Forbidden→404 mapping; unknown exception→500 INTERNAL_ERROR
  (logged, never leaks internals)
- Request IDs: Ktor `CallId` plugin (X-Request-Id gen/propagate) + MDC via
  CallLogging `callIdMdc("request_id")`
- `RateLimit` plugin: sliding-window counters, keys ip:/user:, limits
  `RATE_LIMIT_AUTH_PER_MIN` (10), `RATE_LIMIT_API_PER_MIN` (120); 429 +
  Retry-After; bounded map cleanup
- `/health/ready` — SELECT 1 with 3s timeout
- Logging: keep console `logback.xml`; add `logback-prod.xml` (JSON via
  logstash-logback-encoder) selected by `LOGBACK_CONFIGURATION_FILE`
- DI: manual composition root in ApiApplication (no framework)

## Step 7 — Tests

New module `test-support` (testImplementation shared):
`EmbeddedPostgresExtension` (Zonky), migrates schema, exposes DataSource;
catalog addition `io.zonky.test:embedded-postgres`.

Unit tests:
- Argon2PasswordHasherTest (roundtrip, wrong password, salt uniqueness)
- JwtTokenServiceTest (issue/validate, expiry, tamper, weak-secret rejection)
- EmailTest, PasswordPolicyTest
- RefreshSessionTest with fakes (rotation chain, reuse detection revokes
  family, expired token rejected)
- SlidingWindowRateLimiterTest (window math, key isolation, cleanup)
- Use case tests with fake repos (register creates personal workspace,
  RequireMembership 404 vs role)

Integration tests (embedded PG):
- MigrationTest (V1+V2 apply cleanly, re-run idempotent)
- Repository CRUD + transaction rollback test
- AuthFlowApiTest: register→signin→call /me→refresh→signout; wrong password
  401; duplicate email 409; envelope shape on errors
- **TenantIsolationApiTest**: user B GET/PATCH/DELETE user A's workspace →
  404; B not in member list; B's token cannot read A's resources even with
  valid JWT
- RateLimitApiTest: 11th signin/min → 429 with Retry-After
- AuditTrailTest: expected audit rows after flows

## Step 8 — Docs (same change)

- `docs/adr/ADR-015-self-hosted-jwt-authentication.md` — decision, rationale,
  OIDC swap path, secret management
- DATA_MODEL.md — add `refresh_tokens` table spec
- API.md — exact request/response shapes for register/signin/refresh/signout/
  me/workspaces; rate-limit headers
- DEPLOYMENT.md — env vars: JWT_SECRET, ACCESS_TOKEN_TTL_MINUTES,
  REFRESH_TOKEN_TTL_DAYS, RATE_LIMIT_*; .env.example update
- README.md — Phase 1 status

## Step 9 — Verification

- `cd backend && ./gradlew build` green (unit + integration)
- Smoke run `:server:api:run` + curl /health and /health/ready (DB optional
  for liveness)
- Android untouched

## Out of scope (later phases)

Devices table/endpoints (notifications phase), RLS policies (Phase 14),
email verification/password reset (needs mailer — defer, document), OAuth
social login.
