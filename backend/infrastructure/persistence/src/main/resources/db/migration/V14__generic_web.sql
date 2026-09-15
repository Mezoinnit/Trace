-- Phase 13: generic web intelligence — parser health detail (plan §8 step 12).
ALTER TABLE source_health
    ADD COLUMN parser_fail_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_parser_error TEXT;