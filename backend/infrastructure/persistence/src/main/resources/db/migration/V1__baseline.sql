-- Baseline migration.
-- Proves the versioned migration pipeline (Flyway) works end to end.
-- Real domain schema is added in Phase 1 (multi-tenant backend core).

CREATE TABLE IF NOT EXISTS app_metadata (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_metadata (key, value)
VALUES ('schema', 'baseline')
ON CONFLICT (key) DO NOTHING;