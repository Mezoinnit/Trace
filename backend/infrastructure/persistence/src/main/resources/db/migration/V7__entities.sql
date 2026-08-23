-- Phase 6: entity system — the intelligence identity layer (plan §4.10–4.13).
-- Identity is by structured external identifiers and aliases, never by name
-- alone (plan §4.12). Relational tables only — no graph database in V1.

CREATE TABLE entities (
    id             UUID PRIMARY KEY,
    type           TEXT        NOT NULL,
    canonical_name TEXT        NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entities_type_not_blank CHECK (char_length(type) > 0),
    CONSTRAINT entities_name_not_blank CHECK (btrim(canonical_name) <> '')
);

CREATE UNIQUE INDEX entities_type_name_idx ON entities (type, lower(canonical_name));
-- pg_trgm indexes arrive with the Phase 11 search work (extension + migration there).

CREATE TABLE entity_aliases (
    entity_id UUID NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    alias     TEXT NOT NULL,
    PRIMARY KEY (entity_id, alias)
);

CREATE INDEX entity_aliases_alias_idx ON entity_aliases (lower(alias));

CREATE TABLE entity_identifiers (
    entity_id UUID NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    id_type   TEXT NOT NULL,
    id_value  TEXT NOT NULL,
    PRIMARY KEY (id_type, id_value)
);

CREATE INDEX entity_identifiers_entity_idx ON entity_identifiers (entity_id);

CREATE TABLE entity_relationships (
    id               UUID PRIMARY KEY,
    source_entity_id UUID        NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    target_entity_id UUID        NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    relation         TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_entity_id, target_entity_id, relation),
    CONSTRAINT no_self_relationship CHECK (source_entity_id <> target_entity_id)
);

CREATE TABLE event_entities (
    event_id  UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    entity_id UUID NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    role      TEXT NOT NULL DEFAULT 'SUBJECT',
    PRIMARY KEY (event_id, entity_id)
);

CREATE INDEX event_entities_entity_idx ON event_entities (entity_id);