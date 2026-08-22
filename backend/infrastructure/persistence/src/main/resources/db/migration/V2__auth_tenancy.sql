-- Phase 1: multi-tenant backend core (auth + tenancy).
-- See DATA_MODEL.md. Forward-only, non-destructive.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT        NOT NULL,
    display_name  TEXT,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT users_email_max_len CHECK (char_length(email) <= 320)
);

CREATE UNIQUE INDEX users_email_unique ON users (lower(email));

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    created_by UUID        NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspaces_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT workspaces_name_max_len CHECK (char_length(name) <= 200)
);

CREATE INDEX workspaces_created_by_idx ON workspaces (created_by);

CREATE TABLE workspace_members (
    workspace_id UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT workspace_members_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

CREATE INDEX workspace_members_user_idx ON workspace_members (user_id);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL,
    family_id  UUID        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT refresh_tokens_status CHECK (status IN ('ACTIVE', 'REPLACED', 'REVOKED')),
    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

CREATE TABLE audit_log (
    id            UUID PRIMARY KEY,
    workspace_id  UUID,
    actor_type    TEXT        NOT NULL,
    actor_id      TEXT,
    action        TEXT        NOT NULL,
    resource_type TEXT,
    resource_id   TEXT,
    metadata      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_log_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'JOB'))
);

CREATE INDEX audit_log_workspace_time_idx ON audit_log (workspace_id, created_at DESC);
CREATE INDEX audit_log_action_idx ON audit_log (action, created_at DESC);