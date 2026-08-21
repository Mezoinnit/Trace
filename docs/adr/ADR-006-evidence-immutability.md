# ADR-006: Evidence Immutability

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Sources mutate published content. The platform is a historical intelligence
record: it must be possible to reconstruct exactly how an event was derived and
what a source said at a point in time. Overwriting evidence would silently
destroy history.

## Decision

- Evidence is **immutable or versioned**.
- Insert key: `source_id + external_id` (idempotent — Rule 4).
- When a source changes previously published content, a new
  `evidence_versions` row is appended; never an in-place update.
- Evidence preserves enough information to reconstruct derivation:
  canonical URL, external ID, content hash, metadata, raw payload reference
  when required.
- Full third-party article bodies are not exposed by default (copyright
  posture, plan §12); compact evidence + attribution + canonical link is the
  default shape.

## Consequences

- Strong auditability and historical analysis.
- Storage grows with content change frequency; mitigated by compaction and
  object storage for raw payloads.
- Deduplication (content hash + external id) prevents duplicate evidence.

References: plan §4.6, §4.7, §12, Rule 5.