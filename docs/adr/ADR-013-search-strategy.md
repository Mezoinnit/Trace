# ADR-013: Search Strategy

**Status:** Accepted  
**Date:** 2026-08-21

## Context

V1 needs search across events, entities, evidence, and sources with relevance
ranking and time filtering. A dedicated search engine (Elasticsearch/OpenSearch)
is explicitly avoided until justified by scale (ADR-003).

## Decision

- V1 search uses **PostgreSQL full-text search + `pg_trgm`** over:
  event titles/summaries, entity names/aliases, evidence titles and relevant
  extracted text.
- Cursor pagination and indexed queries keep searches bounded.
- If actual search requirements exceed PostgreSQL capabilities, migrate to
  OpenSearch behind the search module boundary — preemptively adding it is not
  allowed.
- `pgvector` semantic retrieval is added only when justified (and behind the
  same boundary).

## Consequences

- Zero additional infrastructure for V1 search.
- Query patterns are constrained by Postgres; acceptable for the defined
  workloads, revisited at real scale with a documented ADR.

References: plan §21, §29, COST_MODEL.md.