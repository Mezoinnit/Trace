-- Phase 11: search infrastructure (plan §29, ADR-013).
-- PostgreSQL FTS + pg_trgm; no external search service.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Events: full-text over title + summary via a stored generated column.
ALTER TABLE events
    ADD COLUMN search_vec tsvector GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(summary, ''))
    ) STORED;

CREATE INDEX events_fts_idx ON events USING gin (search_vec);

-- Entities (closes the index deferred from V7).
CREATE INDEX entities_name_trgm_idx ON entities
    USING gin (canonical_name gin_trgm_ops);

CREATE INDEX entity_aliases_trgm_idx ON entity_aliases
    USING gin (alias gin_trgm_ops);

-- Evidence titles for traceability searches.
CREATE INDEX evidence_title_trgm_idx ON evidence
    USING gin (title gin_trgm_ops);