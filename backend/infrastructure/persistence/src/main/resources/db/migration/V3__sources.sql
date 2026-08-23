-- Phase 2: source system (sources, endpoints, health).
-- See DATA_MODEL.md. Sources are global shared intelligence (ADR-001);
-- registration is performed by authenticated workspaces' users.

CREATE TABLE sources (
    id                  UUID PRIMARY KEY,
    provider            TEXT        NOT NULL,
    source_type         TEXT        NOT NULL,
    canonical_identity  TEXT        NOT NULL,
    display_name        TEXT,
    trust_classification TEXT       NOT NULL DEFAULT 'UNKNOWN',
    verification_state  TEXT        NOT NULL DEFAULT 'UNVERIFIED',
    capabilities        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sources_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT sources_trust CHECK (trust_classification IN
        ('FIRST_PARTY', 'OFFICIAL', 'ESTABLISHED', 'COMMUNITY', 'UNKNOWN', 'UNTRUSTED')),
    CONSTRAINT sources_verification CHECK (verification_state IN
        ('UNVERIFIED', 'VERIFIED', 'FAILED_VERIFICATION')),
    UNIQUE (provider, source_type, canonical_identity)
);

CREATE TABLE source_endpoints (
    id            UUID PRIMARY KEY,
    source_id     UUID        NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    endpoint_uri  TEXT        NOT NULL,
    display_url   TEXT,
    endpoint_type TEXT        NOT NULL,
    capabilities  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    adapter       TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_endpoints_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    UNIQUE (endpoint_uri)
);

CREATE INDEX source_endpoints_source_idx ON source_endpoints (source_id);

CREATE TABLE source_health (
    endpoint_id          UUID PRIMARY KEY REFERENCES source_endpoints(id) ON DELETE CASCADE,
    state                TEXT        NOT NULL DEFAULT 'HEALTHY',
    last_success_at      TIMESTAMPTZ,
    last_failure_at      TIMESTAMPTZ,
    failure_count        BIGINT      NOT NULL DEFAULT 0,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    average_latency_ms   INTEGER,
    last_item_at         TIMESTAMPTZ,
    last_change_at       TIMESTAMPTZ,
    next_poll_at         TIMESTAMPTZ,
    current_interval     TEXT,
    rate_limit_state     JSONB,
    last_etag            TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_health_state CHECK (state IN
        ('HEALTHY', 'DEGRADED', 'STALE', 'BLOCKED', 'AUTH_REQUIRED',
         'RATE_LIMITED', 'PARSER_FAILED', 'NETWORK_FAILED', 'DISABLED'))
);