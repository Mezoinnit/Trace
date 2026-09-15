-- Phase 13: surface the last parser/discovery outcome even when extraction
-- succeeded, so an operator can see *how* a degraded page produced items
-- (plan §8 step 12, Rule 7 — source health is never hidden).
ALTER TABLE source_health
    ADD COLUMN last_parser_note TEXT;
