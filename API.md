# Trace — API Contract (v1)

> Phase 0 contract. All routes are under `/api/v1`. JSON over HTTPS.
> Versioned; breaking changes bump the version segment, never mutate existing
> endpoints. Android talks only to this API.

## Conventions

- **Auth**: bearer access token (`Authorization: Bearer <token>`). Tokens are
  short-lived; refresh rotation is server-managed (SECURITY.md).
- **Tenant**: the `workspace_id` is never supplied by the client. It is resolved
  from the authenticated identity. Requesting another tenant's resource returns
  `404` (not `403`) to avoid resource enumeration.
- **Errors**: consistent error envelope (below).
- **Pagination**: cursor-based for feeds and large collections. Stable sort keys
  (`occurred_at DESC, id DESC`). See `page` query params.
- **Idempotency**: mutating endpoints accept an `Idempotency-Key` header where a
  retry could create duplicates.
- **Dates**: RFC 3339 UTC (`2026-08-21T12:00:00Z`).

## Error model

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "…",
    "request_id": "req_…",
    "details": [ { "field": "title", "reason": "required" } ]
  }
}
```

Codes: `VALIDATION_ERROR`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`,
`CONFLICT`, `RATE_LIMITED`, `TOO_LARGE`, `PROVIDER_UNREACHABLE`,
`INTERNAL_ERROR`.

## Endpoints

### Authentication

Implemented in Phase 1 (ADR-015). Access tokens are HS256 JWTs (default
15 min); refresh tokens are opaque 256-bit values, rotated on every use with
family-based reuse detection.

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/auth/register` | creates user + personal workspace, returns session |
| POST | `/api/v1/auth/signin` | returns session |
| POST | `/api/v1/auth/refresh` | rotates refresh token; reuse revokes the family |
| POST | `/api/v1/auth/signout` | revokes the presented token's family; idempotent |

`POST /api/v1/auth/register`:

```json
{ "email": "user@example.com", "password": "password-123", "display_name": "Me" }
```

- `201 Created` → session response (below). `409 CONFLICT` when the email is
  registered; `400 VALIDATION_ERROR` for invalid email or weak password
  (min 10 chars, letters + digits).
- Passwords are never logged; hashes are argon2id.

`POST /api/v1/auth/signin` → same session response; wrong credentials return
`401 UNAUTHENTICATED` without revealing account existence.

Session response:

```json
{
  "user": { "id": "…", "email": "…", "display_name": null },
  "workspace": { "id": "…", "name": "Personal Workspace" },
  "access_token": "…",
  "access_token_expires_at": "2026-08-22T12:15:00Z",
  "refresh_token": "…"
}
```

`POST /api/v1/auth/refresh` body `{ "refresh_token": "…" }` → new session
response. Presenting a previously used refresh token is treated as compromise:
the whole family is revoked (`401`) and a `REFRESH_TOKEN_REUSE` audit event is
recorded.

`POST /api/v1/auth/signout` body `{ "refresh_token": "…" }` → `204`; safe to
retry.

### Identity

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/me` | identity + workspaces with roles |
| DELETE | `/api/v1/me` | account deletion; refuses while member of shared workspaces (`409`) |

`GET /api/v1/me` response:

```json
{
  "user": { "id": "…", "email": "…", "display_name": "…" },
  "workspaces": [ { "id": "…", "name": "Personal Workspace", "role": "OWNER" } ]
}
```

### Workspaces

Tenant-owned resource CRUD proving the Phase 1 exit criteria.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/workspaces` | caller's workspaces with roles |
| POST | `/api/v1/workspaces` | create; caller becomes OWNER |
| GET | `/api/v1/workspaces/{id}` | detail; members only |
| PATCH | `/api/v1/workspaces/{id}` | rename; OWNER only |
| DELETE | `/api/v1/workspaces/{id}` | OWNER only |
| GET | `/api/v1/workspaces/{id}/members` | members with roles and emails |

Non-members receive `404` on every operation (never `403`) to prevent tenant
enumeration (ADR-010).

### Events

**Implemented in Phase 4.** Global intelligence feed; authentication required.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/events` | cursor-paginated feed, newest first |
| GET | `/api/v1/events/{id}` | event detail |

Query params: `cursor` (opaque), `limit` (1–100, default 50). Response:

```json
{
  "items": [
    {
      "id": "…", "type": "SOFTWARE_RELEASE",
      "title": "v3.1.0", "summary": "…",
      "occurred_at": "2026-08-10T10:00:00Z",
      "discovered_at": "…",
      "confidence": "HIGH", "significance": "MEDIUM", "status": "CONFIRMED"
    }
  ],
  "next_cursor": "…"
}
```

`GET /api/v1/events/{id}` returns the event **plus its supporting evidence
with source attribution and linked entities** (plan §3.5):

```json
{
  "event": { … },
  "evidence": [
    {
      "id": "…", "title": "v3.1.0", "canonical_url": "https://…",
      "role": "SUPPORTING", "source_name": "acme/widget",
      "source_trust": "OFFICIAL", "published_at": "…"
    }
  ],
  "entities": [ { "id": "…", "name": "acme/widget", "type": "REPOSITORY" } ]
}
```

### Watches

**Implemented in Phase 4** (deterministic core: endpoint + event-type rules).

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/watches` | create; workspace membership verified server-side |
| GET | `/api/v1/watches?workspace_id=…` | list for a workspace |
| DELETE | `/api/v1/watches/{id}` | soft delete (stops matching) |

```json
{
  "workspace_id": "…",
  "name": "Widget releases",
  "endpoint_ids": ["…"],
  "event_types": ["SOFTWARE_RELEASE"],
  "entity_ids": [],
  "keywords_include": ["security"],
  "keywords_exclude": ["beta"],
  "semantic": null,
  "max_age_hours": 720,
  "min_significance": "MEDIUM",
  "min_confidence": null
}
```

Rules: at least one endpoint (max 20), non-blank name; unknown endpoints or
foreign workspaces → `400`/`404`. Empty `event_types` matches all types.
`notification_mode` (`IMMEDIATE` default, `DIGEST`, `NEVER`) persists per
watch (§4.14); DIGEST scheduling activates with the FCM phase — until then
all modes surface alerts in-app.
Bounds (Rule 6): ≤10 entities, ≤20 terms per keyword list (≤64 chars),
semantic ≤500 chars, `max_age_hours` 1–8760. `semantic` is stored but only
evaluated from Phase 10.

`PATCH /api/v1/watches/{id}` requires `expected_version` (optimistic locking;
stale version → `409 CONFLICT`) and accepts any subset of the create fields
plus `status` (`ACTIVE`/`PAUSED`).

### Alerts

**Implemented in Phase 4.** Exactly-once per `(watch_id, event_id)` (ADR-012).

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/alerts?workspace_id=…` | alerts for a verified workspace member |

Push notification dispatch (FCM) arrives with the Android phase; alerts are
queryable and visible in-app from Phase 9 onward.

### Device registration (planned — notifications phase)

### Watches

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/watches` | list (cursor paginated) |
| POST | `/api/v1/watches` | create; validated Watch definition |
| GET | `/api/v1/watches/{id}` | detail |
| PATCH | `/api/v1/watches/{id}` | update (versioned, optimistic lock) |
| DELETE | `/api/v1/watches/{id}` | delete (audited) |

`POST /api/v1/watches` body — the `definition` follows WATCH_MODEL.md:

```json
{
  "name": "AndroidX Releases",
  "definition": {
    "sources": [ { "endpoint_id": "…", "endpoint_type": "RELEASES" } ],
    "entities": [ "Jetpack Compose" ],
    "event_types": ["SOFTWARE_RELEASE"],
    "keywords": { "include": ["compose"], "exclude": [] },
    "min_significance": "LOW",
    "min_confidence": "MEDIUM"
  },
  "notification_policy": { "mode": "IMMEDIATE" }
}
```

### Source resolution and sources

Implemented in Phase 2. Sources are global shared intelligence (ADR-001);
registration is audited. Non-member rules do not apply here — any
authenticated user may resolve/register/list global sources.

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/sources/resolve` | URL → provider/endpoint/capabilities proposal |
| POST | `/api/v1/sources` | register a confirmed proposal (endpoint_uri + adapter_id) |
| GET | `/api/v1/sources` | registry list with endpoints |
| GET | `/api/v1/sources/{id}` | detail incl. per-endpoint health |
| PATCH | `/api/v1/sources/{id}` | enable/disable (cascades to endpoints, audited) |

`POST /api/v1/sources/resolve`:

```json
{ "input": "https://github.com/androidx/androidx/releases" }
```

Response:

```json
{
  "input_url": "https://github.com/androidx/androidx/releases",
  "provider": "GITHUB",
  "source_type": "REPOSITORY",
  "canonical_identity": "androidx/androidx",
  "endpoint_uri": "github://androidx/androidx/releases",
  "display_url": "https://github.com/androidx/androidx/releases",
  "endpoint_type": "RELEASES",
  "adapter_id": "github",
  "capabilities": ["RELEASES", "CONDITIONAL_POLL", "PAGINATED", "RATE_LIMITED_API"],
  "trust": "OFFICIAL",
  "display_name": "androidx/androidx",
  "notes": ["exact endpoint: repository releases"]
}
```

HTML pages with a declared feed return the feed as a structured alternative
(noted); pages without structure return an explicit degraded `web_page`
proposal. Unsupported GitHub paths are rejected — never silently broadened.

`POST /api/v1/sources` — echo only what you are confirming:

```json
{ "endpoint_uri": "github://androidx/androidx/releases", "adapter_id": "github" }
```

All metadata (provider, type, capabilities, trust) is derived server-side from
the validated adapter; `400 VALIDATION_ERROR` for unknown adapters or
unsupported endpoints. Re-registering the same endpoint converges on the same
global rows (idempotent). `201 Created` returns the source detail:

```json
{
  "id": "…",
  "provider": "GITHUB",
  "source_type": "REPOSITORY",
  "canonical_identity": "androidx/androidx",
  "trust": "OFFICIAL",
  "status": "ACTIVE",
  "endpoints": [
    {
      "endpoint": { "id": "…", "endpoint_uri": "…", "adapter": "github", "status": "ACTIVE", … },
      "health": { "state": "HEALTHY", "failure_count": 0, … }
    }
  ]
}
```

`PATCH /api/v1/sources/{id}` body `{ "status": "DISABLED" }` → updated detail.
Disabling cascades to endpoints and is audited (Rule 7: never silently stop
monitoring).

### Events

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/events` | feed; cursor paginated |
| GET | `/api/v1/events/{id}` | detail incl. evidence, entities, timeline refs |

Event detail (see EVENT_MODEL.md for semantics):

```json
{
  "id": "…",
  "type": "SOFTWARE_RELEASE",
  "title": "Compose 1.9 Released",
  "summary": "…",
  "occurred_at": "…",
  "confidence": "HIGH",
  "confidence_reasoning": { "corroboration": 0.9, "source_quality": 0.8 },
  "significance": "HIGH",
  "status": "CONFIRMED",
  "entities": [ { "id": "…", "name": "Jetpack Compose", "type": "SOFTWARE" } ],
  "evidence": [ { "id": "…", "source_name": "androidx", "url": "…", "role": "SUPPORTING" } ],
  "related_events": [ "…" ]
}
```

### Entities

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/entities/{id}` | entity detail |
| GET | `/api/v1/entities/{id}/events` | event timeline for entity (Phase 12) |

### Alerts and notifications

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/notifications` | in-app notification inbox |
| GET | `/api/v1/notifications/{id}` | detail |
| POST | `/api/v1/notifications/{id}/open` | mark opened |
| GET | `/api/v1/alerts` | alerts for debugging; also queryable per Watch |

### Investigations

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/investigations` | natural-language query → structured result (Phase 11) |

### Health

| Method | Path | Notes |
|--------|------|-------|
| GET | `/health` | liveness (no auth) |
| GET | `/health/ready` | readiness incl. database |

## Pagination

Query params: `cursor=<opaque>` and `limit=<1..100>` (default 50). Responses
return:

```json
{
  "items": [ … ],
  "next_cursor": "…"
}
```

`next_cursor` is `null` when the end is reached. Feed cursors are derived from
`(occurred_at DESC, id DESC)`.

## Rate limiting

Limits at IP, account, workspace, endpoint, source, and expensive operation
levels (SECURITY.md §5). Over-limit returns `429` with a `Retry-After` header
and a `RATE_LIMITED` error envelope. Phase 1 defaults (configurable via env):
auth routes 10/min per client IP; general API 120/min per user (IP before
authentication). Single-instance store; shared store deferred until scale
requires it. A dedicated stricter bucket for expensive operations
(source resolution, search) arrives with Phase 8/14 ops hardening.

## Versioning and compatibility

- Additive changes to `/api/v1` are allowed (new fields, new endpoints).
- Contract-breaking changes create `/api/v2` with a deprecation window.
- Server rejects unknown enum values with `VALIDATION_ERROR`; never trusts
  client-provided enums.

## Tenant isolation (API layer)

- The authenticated identity determines the active workspace.
- `workspace_id` in request bodies is rejected or ignored.
- Cross-tenant reads return `404`.