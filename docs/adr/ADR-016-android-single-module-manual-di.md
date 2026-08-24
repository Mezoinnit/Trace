# ADR-016: Android Single-Module Start with Manual DI

**Status:** Accepted
**Date:** 2026-08-24

## Context

The plan recommends a 15+ module Android structure (`:core:*`, `:feature:*`)
and Hilt. At Phase 9 the app has ~10 screens' worth of code; creating empty
Gradle modules adds build time and ceremony without correctness value, and
Hilt's annotation processing adds build fragility before DI needs are clear.

## Decision

Phase 9 ships as **single `:app` module** with packages mirroring the planned
modules (`core/network`, `core/data`, `feature/home`, `feature/sources`,
`feature/watches`, `feature/alerts`, `feature/events`, `ui/components`) and a
**manual composition root** (`TraceApp`) mirroring the backend's ApiContainer
pattern.

Split into `:core:*` / `:feature:*` modules when: two features no longer share
build times well, or a feature needs isolated test tooling. Adopt Hilt when
constructor graphs exceed ~15 dependencies or scope annotations become useful.
Both moves are mechanical because packages already mirror the target modules.

## Consequences

- Faster builds, fewer moving parts during the vertical-slice phase.
- Package boundaries enforce the same dependency direction the modules would.
- Deviation from the plan's *recommended* structure is explicit here; nothing
  in the plan's correctness properties depends on module count.

References: plan §30, ARCHITECTURE.md §12.
