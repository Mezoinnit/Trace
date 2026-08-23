-- Phase 7: watch engine — full rule set (plan §4.14, WATCH_MODEL.md).
-- Column-per-concern keeps rules queryable and validated; the semantic
-- description is stored now and evaluated once the AI layer exists (Phase 10).

ALTER TABLE watches
    ADD COLUMN entity_ids          JSONB      NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN keywords_include    JSONB      NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN keywords_exclude    JSONB      NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN semantic            TEXT,
    ADD COLUMN max_age_hours       INT,
    ADD COLUMN min_significance    TEXT,
    ADD COLUMN min_confidence      TEXT;

CREATE INDEX watches_entities_idx ON watches USING gin (entity_ids jsonb_path_ops);

-- Threshold values are validated at write time by the application layer; a
-- defensive CHECK keeps unknown enum spellings out of storage.
ALTER TABLE watches
    ADD CONSTRAINT watches_min_significance CHECK (
        min_significance IS NULL OR min_significance IN
        ('TRIVIAL', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    ADD CONSTRAINT watches_min_confidence CHECK (
        min_confidence IS NULL OR min_confidence IN
        ('VERY_LOW', 'LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH')),
    ADD CONSTRAINT watches_max_age CHECK (
        max_age_hours IS NULL OR (max_age_hours BETWEEN 1 AND 8760));