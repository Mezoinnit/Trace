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

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/sources/resolve` | URL → provider/endpoint/capabilities proposal (Phase 2) |
| POST | `/api/v1/sources` | create from a confirmed resolution |
| GET | `/api/v1/sources` | list |
| GET | `/api/v1/sources/{id}` | detail incl. health |
| PATCH | `/api/v1/sources/{id}` | enable/disable |
| GET | `/api/v1/sources/{id}/endpoints` | endpoints for a source |

`POST /api/v1/sources/resolve`:

```json
{
  "input": "https://github.com/androidx/androidx/releases"
}
```

Response:

```json
{
  "provider": "GITHUB",
  "resource": "REPOSITORY",
  "owner": "androidx",
  "repository": "androidx",
  "endpoint": "github://androidx/androidx/releases",
  "endpoint_type": "RELEASES",
  "capabilities": ["RELEASES"],
  "adapter": "github"
}
```

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
requires it.

## Versioning and compatibility

- Additive changes to `/api/v1` are allowed (new fields, new endpoints).
- Contract-breaking changes create `/api/v2` with a deprecation window.
- Server rejects unknown enum values with `VALIDATION_ERROR`; never trusts
  client-provided enums.

## Tenant isolation (API layer)

- The authenticated identity determines the active workspace.
- `workspace_id` in request bodies is rejected or ignored.
- Cross-tenant reads return `404`.