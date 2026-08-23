-- Phase 8: durable job queue (ADR-008, plan §22).
-- PostgreSQL-backed; workers claim with FOR UPDATE SKIP LOCKED.

CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    type            TEXT        NOT NULL,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    priority        INT         NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL DEFAULT 'QUEUED',
    attempts        INT         NOT NULL DEFAULT 0,
    max_attempts    INT         NOT NULL DEFAULT 5,
    available_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,
    locked_by       TEXT,
    completed_at    TIMESTAMPTZ,
    failed_at       TIMESTAMPTZ,
    error_code      TEXT,
    error_message   TEXT,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT jobs_status CHECK (status IN
        ('QUEUED', 'LOCKED', 'COMPLETED', 'FAILED', 'DEAD')),
    CONSTRAINT jobs_max_attempts_positive CHECK (max_attempts > 0)
);

-- Claim path: oldest due job first.
CREATE INDEX jobs_claim_idx ON jobs (status, available_at, priority DESC, created_at);

-- Optional dedup for externally-triggered work.
-- Key includes a time bucket from callers, so terminal-state rows never
-- collide with future work.
CREATE UNIQUE INDEX jobs_idempotency_idx ON jobs (idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Lease expiry sweep.
CREATE INDEX jobs_lease_idx ON jobs (status, locked_at)
WHERE status = 'LOCKED';