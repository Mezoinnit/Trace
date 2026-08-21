# ADR-012: Notification Idempotency

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Jobs can be retried; FCM delivery can be retried. Without idempotency, the same
event can produce duplicate alerts and duplicate push notifications, which
destroys user trust and inflates cost.

## Decision

- Unique constraint `(watch_id, event_id)` on **alerts** prevents duplicate
  alert creation (ADR-004).
- Notification delivery uses the idempotency key `alert_id + device_id`
  (unique constraint on `notifications`).
- Retried watch-matching and notification-dispatch jobs are safe to re-run;
  they simply observe the existing rows.
- The same event never re-alerts for the same Watch unless the Watch policy
  explicitly permits it.

## Consequences

- Exactly-one-alert semantics per Watch/event; retry-safe pipelines.
- Requires careful insert-or-ignore handling in the alert/notification writers.

References: plan §23, §33, Rule 4, WATCH_MODEL.md.