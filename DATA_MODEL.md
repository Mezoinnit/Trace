# Trace — Data Model

> Phase 0 contract. PostgreSQL is the single source of truth (ADR-003). All
> timestamps are stored in UTC as `timestamptz`. Tenant-owned tables carry a
> `workspace_id`. Schema is implemented via versioned Flyway migrations under
> `backend/infrastructure/persistence/src/main/resources/db/migration` (Phase 1).

## Conventions

- **IDs**: `UUID` primary keys. Endpoint and evidence identity also use stable
  business keys (see `source_endpoints` and `evidence`).
- **Tenant boundary**: every row below that is owned by a workspace has a
  `workspace_id` column with `NOT NULL` and an index. Queries always filter by
  it. Client-supplied `workspace_id` is never trusted.
- **Timestamps**: `created_at`, `updated_at` on mutable rows; immutable rows
  carry `created_at` only.
- **Enum-like values**: stored as `TEXT` with `CHECK` constraints. Migrations
  add values by altering constraints — never destructive.
- **Idempotency keys**: unique constraints (`workspace_id`, key columns) where
  the plan mandates them.

## Tables

### app_metadata

| column | type | notes |
|--------|------|-------|
| key | text PK | |
| value | text NOT NULL | |
| updated_at | timestamptz NOT NULL | |

### workspaces

| column | type | notes |
|--------|------|-------|
| id | uuid PK | tenant boundary |
| name | text NOT NULL | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

### users

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| email | text UNIQUE NOT NULL | normalized lowercase |
| display_name | text | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

### workspace_members

| column | type | notes |
|--------|------|-------|
| workspace_id | uuid NOT NULL REFERENCES workspaces(id) | |
| user_id | uuid NOT NULL REFERENCES users(id) | |
| role | text NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')) | |
| created_at | timestamptz NOT NULL | |
| PRIMARY KEY (workspace_id, user_id) | | |

### devices

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | tenant-owned |
| user_id | uuid NOT NULL | |
| device_id | text NOT NULL | stable per install; not the token |
| push_token | text | FCM token; can rotate — never identity |
| platform | text NOT NULL CHECK (platform IN ('ANDROID')) | |
| app_version | text | |
| last_seen_at | timestamptz | |
| created_at | timestamptz NOT NULL | |
| UNIQUE (workspace_id, user_id, device_id) | | |

### sources

Logical information source. Global intelligence (not tenant-owned) but
registered/monitored by workspaces.

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| provider | text NOT NULL | e.g. `GITHUB`, `RSS`, `ATOM`, `JSON_API` |
| source_type | text NOT NULL | e.g. `REPOSITORY`, `FEED` |
| canonical_identity | text NOT NULL | stable, provider-scoped (ADR-005) |
| display_name | text | |
| trust_classification | text NOT NULL CHECK (… 'FIRST_PARTY','OFFICIAL','ESTABLISHED','COMMUNITY','UNKNOWN','UNTRUSTED') | |
| verification_state | text NOT NULL DEFAULT 'UNVERIFIED' | |
| capabilities | jsonb NOT NULL DEFAULT '[]' | e.g. `["RELEASES"]` |
| status | text NOT NULL DEFAULT 'ACTIVE' | `ACTIVE`/`DISABLED` |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |
| UNIQUE (provider, source_type, canonical_identity) | | |

### source_endpoints

Exact monitored resource. **The endpoint is uniquely identifiable and is the
monitoring unit.**

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| source_id | uuid NOT NULL REFERENCES sources(id) | |
| endpoint_uri | text NOT NULL | e.g. `github://androidx/androidx/releases` |
| display_url | text | not used as identity |
| endpoint_type | text NOT NULL | e.g. `RELEASES` |
| capabilities | jsonb NOT NULL | |
| adapter | text NOT NULL | selected adapter id |
| status | text NOT NULL DEFAULT 'ACTIVE' | |
| UNIQUE (endpoint_uri) | | stable identity |

### source_credentials

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | tenant-owned |
| source_id | uuid NOT NULL REFERENCES sources(id) | |
| encrypted_secret | text NOT NULL | encrypted at rest |
| key_vault_ref | text | key reference, not the secret |
| scopes | jsonb NOT NULL | minimum required scopes |
| rotation_status | text NOT NULL DEFAULT 'ACTIVE' | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |
| UNIQUE (workspace_id, source_id) | | |

V1 targets public sources without credentials wherever possible.

### source_health

| column | type | notes |
|--------|------|-------|
| endpoint_id | uuid PK REFERENCES source_endpoints(id) | |
| state | text NOT NULL CHECK (… 'HEALTHY','DEGRADED','STALE','BLOCKED','AUTH_REQUIRED','RATE_LIMITED','PARSER_FAILED','NETWORK_FAILED','DISABLED') | |
| last_success_at | timestamptz | |
| last_failure_at | timestamptz | |
| failure_count | bigint NOT NULL DEFAULT 0 | |
| consecutive_failures | int NOT NULL DEFAULT 0 | |
| average_latency_ms | int | |
| last_item_at | timestamptz | |
| last_change_at | timestamptz | |
| next_poll_at | timestamptz | scheduler (ADR-009) |
| current_interval | interval | adaptive interval |
| minimum_interval | interval | |
| maximum_interval | interval | |
| rate_limit_state | jsonb | remaining/reset for rate-limited APIs |

### evidence

Immutable source-derived records. Never overwritten; changes create versions.

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | tenant-owned (who ingested it) |
| source_id | uuid NOT NULL REFERENCES sources(id) | |
| endpoint_id | uuid NOT NULL REFERENCES source_endpoints(id) | |
| external_id | text NOT NULL | provider item id |
| canonical_url | text | |
| title | text | |
| author | text | |
| published_at | timestamptz | |
| discovered_at | timestamptz NOT NULL | |
| content | text | compact/truncated; not full article bodies by default |
| content_hash | text NOT NULL | |
| raw_payload_reference | text | object-storage ref when required |
| metadata | jsonb | extraction/processing metadata |
| language | text | BCP-47 |
| created_at | timestamptz NOT NULL | |
| UNIQUE (workspace_id, source_id, endpoint_id, external_id) | | idempotency key (Rule 4) |
| UNIQUE (content_hash) | | raw dedup |

### evidence_versions

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| evidence_id | uuid NOT NULL REFERENCES evidence(id) | |
| version | int NOT NULL | |
| content | text | |
| content_hash | text NOT NULL | |
| changed_by | text | e.g. source poll |
| created_at | timestamptz NOT NULL | |
| UNIQUE (evidence_id, version) | | |

### events

The core product object. Deduplicated, clustered.

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| type | text NOT NULL | e.g. `SOFTWARE_RELEASE`, `SECURITY_ADVISORY`, … |
| title | text NOT NULL | |
| summary | text | |
| occurred_at | timestamptz | |
| discovered_at | timestamptz NOT NULL | |
| confidence | text CHECK (… 'VERY_LOW','LOW','MEDIUM','HIGH','VERY_HIGH') | or numeric + reasoning, see below |
| confidence_score | numeric | 0..1, inspectable |
| confidence_reasoning | jsonb | per-input scores, not a magic number |
| significance | text CHECK (… 'TRIVIAL','LOW','MEDIUM','HIGH','CRITICAL') | |
| status | text NOT NULL DEFAULT 'CONFIRMED' CHECK (… 'CONFIRMED','CONTESTED','CORRECTED','SUPERSEDED') | |
| external_ref | jsonb | stable refs for re-identification |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |
| INDEX (occurred_at DESC, id DESC) | | cursor pagination key |

### event_evidence

| column | type | notes |
|--------|------|-------|
| event_id | uuid NOT NULL REFERENCES events(id) | |
| evidence_id | uuid NOT NULL REFERENCES evidence(id) | |
| role | text NOT NULL DEFAULT 'SUPPORTING' CHECK (role IN ('SUPPORTING','CONTRADICTING')) | |
| created_at | timestamptz NOT NULL | |
| PRIMARY KEY (event_id, evidence_id) | | |

### entities

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| type | text NOT NULL | e.g. `COMPANY`,`PERSON`,`PRODUCT`,`SOFTWARE`,`REPOSITORY` |
| canonical_name | text NOT NULL | |
| description | text | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |
| UNIQUE (type, canonical_name) | | resolution target |

### entity_aliases

| column | type | notes |
|--------|------|-------|
| entity_id | uuid NOT NULL REFERENCES entities(id) | |
| alias | text NOT NULL | case-normalized |
| PRIMARY KEY (entity_id, alias) | | |

### entity_identifiers

| column | type | notes |
|--------|------|-------|
| entity_id | uuid NOT NULL REFERENCES entities(id) | |
| id_type | text NOT NULL | e.g. `GITHUB_REPO`,`SEC_CIK`,`TICKER`,`DOI`,`ISIN`,`CUSIP`,`PACKAGE`,`URL` |
| id_value | text NOT NULL | |
| PRIMARY KEY (id_type, id_value) | | identity by structured id, not name |

### entity_relationships

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| source_entity_id | uuid NOT NULL REFERENCES entities(id) | |
| target_entity_id | uuid NOT NULL REFERENCES entities(id) | |
| relation | text NOT NULL | e.g. `owns`,`develops`,`contains`,`listed_as` |
| created_at | timestamptz NOT NULL | |
| UNIQUE (source_entity_id, target_entity_id, relation) | | |

### event_entities

| column | type | notes |
|--------|------|-------|
| event_id | uuid NOT NULL REFERENCES events(id) | |
| entity_id | uuid NOT NULL REFERENCES entities(id) | |
| role | text | e.g. `ACTOR`,`SUBJECT` |
| PRIMARY KEY (event_id, entity_id) | | |

### watches

Persistent monitoring intent. User-specific.

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | tenant-owned |
| created_by | uuid NOT NULL REFERENCES users(id) | |
| name | text NOT NULL | |
| definition | jsonb NOT NULL | structured rules, validated |
| status | text NOT NULL DEFAULT 'ACTIVE' | `ACTIVE`/`PAUSED`/`DELETED` |
| notification_policy | jsonb NOT NULL | per Watch notification rules |
| version | int NOT NULL DEFAULT 1 | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

Watch `definition` shape is specified in WATCH_MODEL.md. Complexity is bounded
(Rule 6).

### alerts

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | tenant-owned |
| watch_id | uuid NOT NULL REFERENCES watches(id) | |
| event_id | uuid NOT NULL REFERENCES events(id) | |
| relevance | jsonb | why it matched |
| significance | text | snapshot at creation |
| status | text NOT NULL DEFAULT 'PENDING' | |
| created_at | timestamptz NOT NULL | |
| UNIQUE (watch_id, event_id) | | alert idempotency (ADR-012) |

### notifications

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| alert_id | uuid NOT NULL REFERENCES alerts(id) | |
| device_id | uuid NOT NULL REFERENCES devices(id) | |
| status | text NOT NULL | `QUEUED`,`SENT`,`FAILED`,`OPENED` |
| channel | text NOT NULL DEFAULT 'FCM' | |
| fcm_message_id | text | |
| sent_at | timestamptz | |
| opened_at | timestamptz | |
| created_at | timestamptz NOT NULL | |
| UNIQUE (alert_id, device_id) | | delivery idempotency (ADR-012) |

### jobs

PostgreSQL job queue (ADR-008).

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| type | text NOT NULL | see ARCHITECTURE.md §7 |
| payload | jsonb NOT NULL | typed |
| priority | int NOT NULL DEFAULT 0 | |
| status | text NOT NULL | `QUEUED`,`LOCKED`,`COMPLETED`,`FAILED`,`DEAD` |
| attempts | int NOT NULL DEFAULT 0 | |
| max_attempts | int NOT NULL DEFAULT 5 | |
| available_at | timestamptz NOT NULL | backoff |
| locked_at | timestamptz | visibility timeout |
| locked_by | text | worker id |
| completed_at | timestamptz | |
| failed_at | timestamptz | |
| error_code | text | |
| error_message | text | |
| idempotency_key | text | optional uniqueness |
| created_at | timestamptz NOT NULL | |

### audit_log

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| workspace_id | uuid | nullable for system-level |
| actor_type | text NOT NULL | `USER`,`SYSTEM`,`JOB` |
| actor_id | text | |
| action | text NOT NULL | e.g. `WATCH_CREATED`,`SOURCE_DISABLED`,`EVENT_CREATED` |
| resource_type | text | |
| resource_id | text | |
| metadata | jsonb | |
| created_at | timestamptz NOT NULL | |
| INDEX (workspace_id, created_at DESC) | | |

### ai_operations

Reproducibility metadata for AI-assisted decisions (ADR-007).

| column | type | notes |
|--------|------|-------|
| id | uuid PK | |
| operation | text NOT NULL | e.g. `CLASSIFY_EVENT` |
| provider | text NOT NULL | e.g. `LOCAL` |
| model | text | |
| input_ref | text | pointer to the input |
| output_json | jsonb | validated output |
| tokens_used | int | |
| latency_ms | int | |
| cache_hit | boolean NOT NULL DEFAULT false | |
| created_at | timestamptz NOT NULL | |

## Relationship summary

```text
workspaces 1─n workspace_members n─1 users
workspaces 1─n devices
sources 1─n source_endpoints 1─1 source_health
sources 1─n source_credentials (per workspace)
source_endpoints 1─n evidence 1─n evidence_versions
evidence n─n events        (via event_evidence, with role)
events n─n entities        (via event_entities)
entities 1─n entity_aliases / entity_identifiers
entities n─n entities      (via entity_relationships)
watches 1─n alerts n─1 events
alerts 1─n notifications n─1 devices
```

## Indexing rules

- Indexes reflect actual access patterns (event feeds, tenant lookups, watch
  matching, scheduler polling), not every column.
- Feed queries use `(occurred_at DESC, id DESC)` cursor keys.
- Every `workspace_id` foreign key is indexed.
- Performance is validated with `EXPLAIN ANALYZE` before production
  (plan §46).

## Migration strategy

- Versioned SQL migrations: `V{n}__description.sql`.
- Forward-only, non-destructive. No `fallbackToDestructiveMigration`.
- Enum additions alter `CHECK` constraints in new migrations.
- See DEPLOYMENT.md for the full strategy.