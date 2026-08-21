# ADR-002: Modular Monolith

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Microservices add operational cost and complexity that are unjustified at V1
scale. The product needs clear domain boundaries so future domains can be added
without forking the engine, and the ability to split later if real scale demands
it.

## Decision

- Backend is a **modular monolith**: one codebase, strict internal module
  boundaries, multiple deployable units (API, Worker, Scheduler) that share
  domain code.
- Dependency direction: `server → infrastructure → application → domain`.
  `domain` has no framework/database/AI dependencies.
- The deployable units may be split into independent services later without
  redesigning domain code.

## Consequences

- Simpler operations and deployment than microservices at V1 scale.
- Requires disciplined module boundaries to avoid the monolith becoming a blob.
- Logical modules: auth, tenancy, sources, ingestion, evidence, entities,
  events, intelligence, watches, alerts, notifications, search, ai, scheduler,
  market (future), observability.

References: plan §20, §2.2.