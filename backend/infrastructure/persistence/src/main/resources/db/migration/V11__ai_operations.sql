-- Phase 10: AI operations reproducibility metadata (plan §17–18, DATA_MODEL).
-- Every AI-assisted decision records provider/model/input/output so it can
-- be audited and replayed. The deterministic LocalProvider writes rows too.

CREATE TABLE ai_operations (
    id          UUID PRIMARY KEY,
    operation   TEXT        NOT NULL,
    provider    TEXT        NOT NULL,
    model       TEXT,
    input_ref   TEXT,
    output_json JSONB       NOT NULL DEFAULT '{}'::jsonb,
    tokens_used INT         NOT NULL DEFAULT 0,
    latency_ms  INT         NOT NULL DEFAULT 0,
    cache_hit   BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ai_operations_operation CHECK (char_length(operation) > 0)
);

CREATE INDEX ai_operations_op_time_idx ON ai_operations (operation, created_at DESC);