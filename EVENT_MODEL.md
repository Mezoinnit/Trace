# Trace — Event Model

> Phase 0 contract. The event is the central product object: **one event, many
> pieces of evidence** (ADR-004).
>
> **Implementation status:** Phase 5 ships the event engine on top of the
> Phase 4 slice: source-scoped clustering candidates with a **version-identity
> hard gate** (`v3.0.0` ≠ `v3.1.0`), title-Jaccard ≥ 0.6 within a 72 h window,
> deterministic `ConfidenceCalculator` storing `confidence_score` +
> `confidence_reasoning` (corroboration counted across distinct endpoints),
> and a `MarkEventContested` transition preserving contradicting evidence
> (CONTESTED status, CONTRADICTING role) — never silently merged.
>
> Phase 4 originally shipped:
> SOFTWARE_RELEASE events derived from GitHub release evidence, keyed by a
> unique `external_ref` (`provider` + `external_id`) so pipeline replays never
> duplicate. Confidence defaults HIGH for first-party structured data;
> significance LOW for prerelease/draft, MEDIUM otherwise. Clustering,
> multi-signal dedup, contradiction detection, and inspectable confidence
> reasoning arrive in Phases 5–6 behind the same `events` schema.

## 1. Semantics

An **Event** is "something that happened". A release announcement, a GitHub
release, a changelog entry, and a third-party article can all be **evidence**
for the **same** event.

```text
EVENT (SOFTWARE_RELEASE, "Compose 1.9 Released")
├── Evidence A → official announcement      (role: SUPPORTING)
├── Evidence B → GitHub release             (role: SUPPORTING)
├── Evidence C → official changelog         (role: SUPPORTING)
└── Evidence D → third-party article        (role: SUPPORTING)
```

The UI presents one event with multiple supporting sources, never duplicate news
cards.

## 2. Event types

V1 types (TEXT + CHECK, extendable by migration):

```text
SOFTWARE_RELEASE   SECURITY_ADVISORY   PRODUCT_LAUNCH
ANNOUNCEMENT       ARTICLE_PUBLISHED   EARNINGS
SEC_FILING         ACQUISITION         CONTRACT
DELIVERY           POLICY_CHANGE       RESEARCH_PUBLICATION
CUSTOM
```

Additional domains add types without replacing the core pipeline.

## 3. Fields

See DATA_MODEL.md `events`. Core semantics:

| field | notes |
|-------|-------|
| `type` | validated enum |
| `title` / `summary` | human-readable |
| `occurred_at` | when it happened (best-known) |
| `discovered_at` | when the system first saw it |
| `confidence` | separate from source trust (§5) |
| `significance` | how much it matters (§6) |
| `status` | `CONFIRMED` / `CONTESTED` / `CORRECTED` / `SUPERSEDED` |
| `external_ref` | stable refs for re-identification |

## 4. Event lifecycle

```text
CANDIDATE (extracted from evidence)
   → CLUSTERED / DEDUPLICATED
   → CONFIRMED        (corroboration threshold met)
   → CONTESTED        (contradicting evidence present)
   → CORRECTED        (new authoritative evidence corrects it)
   → SUPERSEDED       (a later event replaces it)
```

`CONTESTED` is a deliberate state: conflicting evidence is preserved and
surfaced, never silently merged (Rule 5).

## 5. Confidence

Confidence is **not** source trust (a first-party source is not guaranteed
truth; an unknown source is not automatically false).

Inputs to the scoring pipeline (all recorded in `confidence_reasoning` so the
score is inspectable — never a mysterious LLM number):

```text
source quality
number of independent corroborating sources
first-party confirmation
extraction confidence
entity resolution confidence
event classification confidence
contradiction presence
temporal consistency
identifier consistency
```

The pipeline combines inputs deterministically; AI may assist with inputs such
as extraction or classification but is never the sole authority. Store both a
score (`confidence_score` 0..1) and the reasoning.

## 6. Significance

Not every event deserves an alert. Inputs:

```text
event type, entity relevance, watch relevance, novelty, magnitude,
source authority, confidence, user preferences
```

Examples:

```text
README typo → TRIVIAL          minor documentation change → LOW
major release → HIGH           security advisory → VERY HIGH
critical vulnerability → CRITICAL   major acquisition → CRITICAL
```

Thresholds are configurable per Watch (WATCH_MODEL.md).

## 7. Deduplication and clustering

Raw evidence dedup: `canonical_url`, `external_id`, `content_hash`, provider
identifier.

Event dedup uses multiple signals (never a single similarity threshold):

```text
same entities, same event type, temporal proximity, similar facts,
semantic similarity, shared identifiers, source relationships
```

```text
Evidence → raw duplicate detection → candidate event → event similarity →
event clustering → existing event OR new event
```

## 8. Contradiction

Conflicting evidence is stored with `role = CONTRADICTING` in `event_evidence`,
the event status becomes `CONTESTED`, and the UI must expose the disagreement
with supporting/contradicting evidence and confidence on each side.

## 9. Versioning and corrections

Historical intelligence is never silently mutated:

- Evidence changes create `evidence_versions` (never overwrite).
- Event corrections create `CORRECTED`/`SUPERSEDED` states or correction
  records, with audit entries (OBSERVABILITY.md).

## 10. Timeline position

Events are ordered by `occurred_at` for timelines. Related events are linked
via shared entities and relationships (entities layer) and, later, explicit
event relationships (Phase 19).