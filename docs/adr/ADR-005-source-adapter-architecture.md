# ADR-005: Source Adapter Architecture

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Users add sources by URL or provider explorer. Providers differ wildly (GitHub
API, RSS, Atom, REST/JSON, generic HTML). The system must add providers without
touching the core pipeline and must never silently broaden an exact endpoint
into an entire provider.

## Decision

- A stable `SourceAdapter` contract: `validateEndpoint, discover, fetch,
  normalize, getCapabilities, getCursor, advanceCursor, healthCheck`.
- **Exact endpoints** are the monitoring unit with stable identities (e.g.
  `github://androidx/androidx/releases`), never a display URL.
- Structured data is preferred (API > webhook > feed > JSON-LD > HTML). Generic
  HTML extraction is a **degraded adapter**.
- Every adapter passes a shared fixture-based contract test suite.
- Only adapters required by the current phase are implemented.

## Consequences

- New domains add adapters, entity types, event types, and validation rules
  without forking the engine.
- Generic web support is explicitly lower-quality and marked degraded.
- Adapter quality is enforced by contract tests in CI.

References: plan §6, §7, §8, SOURCE_ADAPTERS.md.