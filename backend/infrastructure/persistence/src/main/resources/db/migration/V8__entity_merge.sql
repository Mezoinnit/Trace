-- Phase 6 audit fix A1: false-merge recovery for entities.
-- The loser keeps its row and points at the cluster survivor; identifiers,
-- aliases, and event links are moved so lookups converge. Reversal data is
-- recorded in the ENTITY_MERGED audit metadata (Rule 5).

ALTER TABLE entities
    ADD COLUMN merged_into_entity_id UUID REFERENCES entities(id);

CREATE INDEX entities_merged_into_idx ON entities (merged_into_entity_id);