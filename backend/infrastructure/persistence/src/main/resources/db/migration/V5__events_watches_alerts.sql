-- Phase 4: GitHub vertical slice — events, watches, alerts.
-- See DATA_MODEL.md, EVENT_MODEL.md, WATCH_MODEL.md. Alert uniqueness is the
-- Phase 4 correctness guarantee: (watch_id, event_id) is unique (ADR-012).

CREATE TABLE events (
    id            UUID PRIMARY KEY,
    type          TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    summary       TEXT,
    occurred_at   TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confidence    TEXT        NOT NULL DEFAULT 'MEDIUM',
    significance  TEXT        NOT NULL DEFAULT 'MEDIUM',
    status        TEXT        NOT NULL DEFAULT 'CONFIRMED',
    external_ref  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT events_type CHECK (char_length(type) > 0),
    CONSTRAINT events_confidence CHECK (confidence IN
        ('VERY_LOW', 'LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH')),
    CONSTRAINT events_significance CHECK (significance IN
        ('TRIVIAL', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT events_status CHECK (status IN
        ('CONFIRMED', 'CONTESTED', 'CORRECTED', 'SUPERSEDED'))
);

-- Deterministic re-identification of pipeline-derived events (Rule 4):
-- a release event is keyed by its provider identity, so replays never
-- create duplicates.
CREATE UNIQUE INDEX events_external_ref_unique ON events (
    (external_ref ->> 'provider'),
    (external_ref ->> 'external_id')
)
WHERE external_ref ?& ARRAY['provider', 'external_id'];

CREATE INDEX events_occurred_idx ON events (occurred_at DESC NULLS LAST, id DESC);
CREATE INDEX events_type_idx ON events (type);

CREATE TABLE event_evidence (
    event_id    UUID        NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    evidence_id UUID        NOT NULL REFERENCES evidence(id) ON DELETE CASCADE,
    role        TEXT        NOT NULL DEFAULT 'SUPPORTING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, evidence_id),
    CONSTRAINT event_evidence_role CHECK (role IN ('SUPPORTING', 'CONTRADICTING'))
);

CREATE INDEX event_evidence_evidence_idx ON event_evidence (evidence_id);

CREATE TABLE watches (
    id                   UUID PRIMARY KEY,
    workspace_id         UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    created_by           UUID        NOT NULL REFERENCES users(id),
    name                 TEXT        NOT NULL,
    endpoint_ids         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    event_types          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status               TEXT        NOT NULL DEFAULT 'ACTIVE',
    version              INT         NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT watches_status CHECK (status IN ('ACTIVE', 'PAUSED', 'DELETED')),
    CONSTRAINT watches_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX watches_workspace_idx ON watches (workspace_id, status);

CREATE TABLE alerts (
    id           UUID PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    watch_id     UUID        NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
    event_id     UUID        NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    relevance    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    significance TEXT        NOT NULL DEFAULT 'MEDIUM',
    status       TEXT        NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Exactly-once alerting per watch/event (plan §33, ADR-012).
    UNIQUE (watch_id, event_id)
);

CREATE INDEX alerts_workspace_created_idx ON alerts (workspace_id, created_at DESC);
CREATE INDEX alerts_event_idx ON alerts (event_id);