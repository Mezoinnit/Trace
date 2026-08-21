# ADR-004: Event-Centric Domain

**Status:** Accepted  
**Date:** 2026-08-21

## Context

A conventional news feed treats articles as the primary object. That produces
duplicate cards for the same underlying event (e.g. a release announced by the
vendor, released on GitHub, and reported by third parties). The product promise
is *"one event, many sources"*.

## Decision

- The primary objects are: **Sources, Evidence, Events, Entities, Watches,
  Alerts, Timelines, Investigations**. An article is evidence for an event, not
  a first-class feed item.
- Events are deduplicated and clustered from evidence (multi-signal similarity,
  never a single threshold — plan §13).
- Conflicting evidence is preserved and marked `CONTESTED`, never silently
  merged (Rule 5).
- Idempotency is built into the pipeline: `source_id + external_id` for
  evidence, `watch_id + event_id` for alerts.

## Consequences

- The UI presents one event with multiple supporting sources.
- Requires a robust clustering/dedup stage before events become visible.
- Domain model is richer but matches the product's intelligence goals and
  future multi-domain support.

References: plan §2.1, §2.2, §4.8, §13.