-- Phase 3: evidence system — immutable, traceable, deduplicated.
-- See DATA_MODEL.md and ADR-006. Evidence is global shared intelligence
-- (plan §34, ADR-001): one ingestion fans out to all watchers.

CREATE TABLE evidence (
    id                    UUID PRIMARY KEY,
    source_id             UUID        NOT NULL REFERENCES sources(id),
    endpoint_id           UUID        NOT NULL REFERENCES source_endpoints(id),
    external_id           TEXT        NOT NULL,
    canonical_url         TEXT,
    title                 TEXT,
    author                TEXT,
    published_at          TIMESTAMPTZ,
    discovered_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    content               TEXT,
    content_hash          TEXT        NOT NULL,
    raw_payload_reference TEXT,
    metadata              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    language              TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Idempotency key (Rule 4): repeated fetches of the same item never
    -- create duplicate evidence.
    UNIQUE (endpoint_id, external_id),
    CONSTRAINT evidence_content_hash_present CHECK (char_length(content_hash) > 0)
);

CREATE INDEX evidence_content_hash_idx ON evidence (content_hash);
CREATE INDEX evidence_endpoint_discovered_idx ON evidence (endpoint_id, discovered_at DESC);
CREATE INDEX evidence_published_idx ON evidence (published_at DESC NULLS LAST, id DESC);

-- Content changes append versions; history is never overwritten (ADR-006).
CREATE TABLE evidence_versions (
    id           UUID PRIMARY KEY,
    evidence_id  UUID        NOT NULL REFERENCES evidence(id) ON DELETE CASCADE,
    version      INT         NOT NULL,
    content      TEXT,
    content_hash TEXT        NOT NULL,
    changed_by   TEXT        NOT NULL DEFAULT 'source_poll',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evidence_id, version)
);