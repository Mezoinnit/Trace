# Trace — Watch Model

> Phase 0 contract. A Watch is a persistent monitoring intent. It is
> user-specific state; the definition is validated and bounded.
>
> **Implementation status:** Phase 7 completes the deterministic rule set.
> `WatchEvaluator` is a pure function over: endpoint containment, event type,
> entity constraints (via the Phase 6 graph), keyword include/exclude on
> title+summary, a recency window (`max_age_hours`), and significance/
> confidence band thresholds. Rejection reasons are recorded per match for
> explainability. Bounds: ≤20 endpoints, ≤10 entities, ≤20 terms per keyword
> list (≤64 chars each), semantic ≤500 chars, age 1–8760 h.
>
> The `semantic` field is stored and editable now but deliberately NOT part
> of deterministic matching; it activates with the AI layer (Phase 10).
> Alerts remain exactly-once via the `(watch_id, event_id)` unique constraint
> (ADR-012), independent of evaluator results. Workspace membership is verified server-side on every
> operation (client-supplied workspace_id is a claim, not a trust anchor).

## 1. Definition

```json
{
  "sources": [
    { "endpoint_id": "…", "endpoint_type": "RELEASES" }
  ],
  "entities": [ "Jetpack Compose", "AndroidX" ],
  "event_types": ["SOFTWARE_RELEASE"],
  "keywords": { "include": ["compose", "stable"], "exclude": ["alpha"] },
  "semantic": { "description": "Compose-related changes" },
  "time": { "max_age": "P30D" },
  "min_significance": "LOW",
  "min_confidence": "MEDIUM",
  "notification_policy": { "mode": "IMMEDIATE" }
}
```

Constraints (validated server-side, bounded per Rule 6):

| field | bounds |
|-------|--------|
| `sources` | max N endpoints per Watch |
| `entities` | max N entities |
| `keywords.include` / `.exclude` | max N terms, max length per term |
| `semantic.description` | max length; AI-assisted only with user confirmation |
| `notification_policy` | validated enum of modes |

A Watch with no constraints is rejected (an empty "watch everything" rule is an
overly broad monitoring rule).

## 2. Watch → Alert pipeline

```text
New Event
 ↓ Watch matching (definition constraints evaluated deterministically)
 ↓ Relevance check
 ↓ Significance threshold
 ↓ Confidence threshold
 ↓ Alert created (idempotent)
```

Matching is deterministic where possible. Semantic filters may use AI, but AI is
never the sole authority for identity or idempotency (AI.md).

## 3. Alert idempotency

- Unique constraint `(watch_id, event_id)` prevents duplicate alerts.
- The same event never re-alerts for the same Watch unless the Watch policy
  explicitly permits it.
- Matching is safe to retry (Rule 4).

## 4. Notification policy

V1 modes:

```text
IMMEDIATE     → notify as soon as an alert is created
DIGEST        → group alerts by schedule (future)
NEVER         → in-app only
```

Per-Watch overrides and workspace defaults both supported. Alerts are always
visible in-app even when FCM is unavailable.

## 5. Alert record

An Alert snapshots why it matched:

```json
{
  "id": "…",
  "watch_id": "…",
  "event_id": "…",
  "relevance": { "matched_sources": ["…"], "matched_keywords": ["compose"],
                 "significance_at_creation": "HIGH" },
  "status": "PENDING"
}
```

## 6. Watch CRUD rules

- Create: full validated definition.
- Update: versioned with optimistic locking (`version` column). Audit record on
  change (OBSERVABILITY.md).
- Delete: soft delete (`status = DELETED`) so history remains resolvable.

## 7. Public / shared watches (future)

Long-term shape (Phase 18): `Watch Definition → Subscriptions → Users`. A public
Watch is versioned; subscribers opt into a version and a creator cannot silently
change what existing subscribers monitor. Not in V1.

## 8. Matching guarantees

- Deterministic rules produce identical results across retries and workers.
- Bounded evaluation: a Watch may not scan unbounded event history; matching
  uses indexed event queries scoped to the Watch's time window.
- False-positive feedback is recorded as observability signal (watch_matches,
  false_positive_feedback).