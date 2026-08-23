-- Phase 5: event engine — inspectable confidence and clustering support.
-- See EVENT_MODEL.md, plan §13–§15. Non-destructive additions only.

ALTER TABLE events
    ADD COLUMN confidence_score NUMERIC(3, 2),
    ADD COLUMN confidence_reasoning JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Cluster bookkeeping: when evidence from a second source is attached to an
-- existing event, that event becomes the cluster representative. Merged
-- duplicates keep a pointer so history stays reconstructable (Rule 5).
ALTER TABLE events
    ADD COLUMN merged_into_event_id UUID REFERENCES events(id);