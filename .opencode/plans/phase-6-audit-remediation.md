# Audit: Phases 0–6 vs Master Plan — Findings & Remediation

> Scope: verify the first six phases are gap-free before starting Phase 7.
> Method: code-level spot checks against plan sections, exit criteria, and
> engineering rules. All items below verified in source on 2026-08-23.

## Verdict

Core guarantees hold: idempotency keys are constraint-backed and tested;
tenant isolation is enforced and tested; SSRF/XXE defenses are implemented
with test matrices; migrations are forward-only; secrets stay in env; fetch
runs outside DB transactions; refresh reuse detection works.

**Five real gaps need remediation before Phase 7** (A1–A5). Items in B are
deferred-by-design and only need doc markers so nothing looks silently dropped.

---

## A. Gaps to fix now

### A1. Entity false-merge recovery is claimed but not built
- Phase 6 exit criterion: "False merges must be recoverable."
- ResolveEntity's doc says "recoverable" but no merge/unmerge exists.
- **Fix:** `V8__entity_merge.sql` adds `entities.merged_into_entity_id`.
  New `MergeEntities` use case: validate both exist / not self / same type,
  move identifiers + aliases + event_entities links to the survivor, point
  loser at survivor, write `ENTITY_MERGED` audit entry whose metadata records
  the moved identifier rows (operator-reversible). Idempotent on replay.
- Test: merge two repo entities → lookups by old identifier resolve to
  survivor; event links preserved; replay is a no-op.

### A2. Watch API surface + audit trail incomplete
- API.md promises `GET/PATCH /api/v1/watches/{id}`; plan §43 requires audit
  records for watch created/changed/deleted. Today: create/list/delete exist,
  zero audit entries, `version` column unused.
- **Fix:** add GET + PATCH routes; audit `WATCH_CREATED`, `WATCH_UPDATED`,
  `WATCH_DELETED`; PATCH accepts name/status/event_types/endpoint_ids and
  enforces optimistic locking (`version` mismatch → `409 CONFLICT`).
- Tests: patch happy path, version-conflict 409, audit rows present.

### A3. Account deletion (`DELETE /me`) untested
- Phase 1 requirement (plan §26) with cascade + refusal semantics, no test.
- **Fix:** integration tests — deletes user + sole workspace + revokes
  sessions; returns 409 while member of a shared workspace; requires auth.

### A4. Adapter pagination cursors never used
- Plan Phase 4 lists "pagination/cursors"; `FetchEndpointNow` always passes
  `cursor = null`, so repositories with >30 releases only ever ingest page 1.
- **Fix:** bounded backfill loop — after page 1, keep fetching while
  `nextCursor != null` and the previous page was full, max 3 extra cycles
  (Rule 6). Newest-first APIs make steady-state single-page normal; the loop
  covers initial backfill deterministically.
- Test: stub serving 2 full pages → all items ingested in one cycle; loop
  stops at bound.

### A5. Adapter contract test suite promised but missing
- SOURCE_ADAPTERS.md: "Every adapter must pass a common fixture-based suite."
- **Fix:** abstract contract test covering: valid endpoint accepted, invalid
  rejected, malformed payload → PARSER_FAILED-class failure, 304 → empty
  result keeping etag, 429 → RATE_LIMITED failure. Extend for rss/atom/github
  with micro-fixtures; web_page exempt (degraded adapter).

## B. Deferred-by-design — add explicit doc markers only

| # | Item | Where noted | Lands in |
|---|------|-------------|----------|
| B6 | No metrics facade yet | OBSERVABILITY.md "phase status" | Phase 8 |
| B7 | STALE health state unused | SOURCE_ADAPTERS.md | Phase 8 |
| B8 | robots.txt check absent | plan §8 step 3 → SOURCE_ADAPTERS §generic | Phase 13 |
| B9 | Per-source fetch concurrency caps | SECURITY.md §6 | Phase 8 workers |
| B10 | jobs/devices/notifications/ai_operations tables | DATA_MODEL.md "planned Phase N" labels | 8/9 |
| B11 | Device/session management (plan §26) | README parked list | Phase 9 |
| B12 | Dedicated resolve rate-limit bucket | API.md rate-limit section | Phase 8/14 |
| B13 | Semantic-similarity clustering signal | EVENT_MODEL.md | Phase 10 |

## Execution order

1. A1 (migration + use case + tests)
2. A2 (routes + audit + optimistic lock + tests)
3. A3 (tests only)
4. A4 (bounded backfill + test)
5. A5 (contract test base + three adapters)
6. B-markers in five docs
7. Full build, commit as "Phase 0–6 audit remediation"

Estimated delta: 1 migration, ~6 new/changed main files, ~8 new test cases,
5 doc touch-ups. No contract-breaking changes; API additions only.
