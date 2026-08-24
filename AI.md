# Trace — AI Gateway and Usage Rules

> Phase 0 contract.
>
> **Phase 10 implementation status:**
> - `AiGateway` port lives in the application layer; providers implement
>   `AiProvider` in the `:ai` module. The domain never imports SDKs.
> - **LocalProvider shipped**: `RuleBasedLocalProvider` — deterministic,
>   $0, offline NL watch interpretation (GitHub URLs / `owner/repo` tokens,
>   quoted or cue-phrase keywords, "only major/critical" significance floor)
>   plus token-overlap semantic relevance that activates the stored watch
>   `semantic` field during matching.
> - Selection is configuration-driven (`AI_PROVIDER`, default `local`);
>   Gemini/OpenAI plug in as additional `AiProvider`s without architecture
>   change — never as hard dependencies.
> - Input limit 500 chars enforced at the gateway; provider output is
>   validated defensively; every operation is recorded to `ai_operations`
>   via a transaction-aware recorder for reproducibility.
> - Without AI configured, ingestion/matching/alerting continue; the
>   semantic constraint degrades to pass-with-note (`semantic_evaluated=false`
>   in the alert relevance map). AI is an intelligence **layer**, never the system of record
> (plan §2.5, ADR-007). The core domain stays deterministic and auditable and
> must operate with AI unavailable.

## 1. Abstraction

All AI access goes through `AiGateway`. The domain never imports provider SDKs.

```text
AiGateway
├── classifyEvent()
├── extractEntities()
├── resolveEntity()
├── summarizeEvidence()
├── summarizeEvent()
├── parseWatchIntent()
├── parseInvestigationQuery()
└── detectContradiction()
```

Providers (configuration-driven, pluggable):

```text
LocalProvider      (V1 development default — $0)
GeminiProvider     (configurable, never a hard dependency)
OpenAiProvider     (configurable)
AnthropicProvider  (configurable)
```

Provider selection is configuration-driven, not code-driven.

## 2. What AI may do

- entity extraction and resolution assistance;
- event classification / extraction;
- summarization (evidence, event, timelines);
- semantic relevance;
- contradiction analysis;
- natural-language Watch creation and investigation queries.

## 3. What AI must never decide alone

- source identity and exact endpoint identity;
- authorization, tenant isolation, security, billing;
- database integrity and notification idempotency;
- final event identity where deterministic validation is possible.

## 4. Trust and validation

- Every AI output is **untrusted structured input**.
- Outputs are validated against strict schemas; invalid outputs are rejected and
  the operation falls back to deterministic processing or fails safely.
- Token/input limits are enforced per operation (Rule 6).
- Deterministic AI results are cached where safe.

## 5. Reproducibility

Every AI-assisted decision records provider, model, input ref, validated output,
tokens, latency, and cache status in `ai_operations` (DATA_MODEL.md). This makes
decisions inspectable and reproducible.

## 6. Graceful degradation

If AI is unavailable:

- ingestion, normalization, storage, structured event processing, and
  deterministic Watch notification all continue;
- AI-assisted steps (semantic filters, summaries, NL parsing) degrade to
  deterministic behavior or are skipped and marked;
- the user is informed when an AI-assisted feature is degraded.

## 7. Cost discipline

- Do not send every piece of evidence to an LLM.
- Prefer deterministic parsing for known structured events.
- Local provider for development; paid providers are optional configuration
  (COST_MODEL.md, Rule 10).
- Per-tenant and per-operation AI quotas with backpressure.

## 8. Natural-language Watch creation (Phase 10)

`parseWatchIntent("Monitor new releases in androidx/androidx and notify me when
Compose changes")` produces a **structured Watch proposal**:

```json
{
  "sources": [ { "endpoint": "github://androidx/androidx/releases", "endpoint_type": "RELEASES" } ],
  "entities": ["Jetpack Compose"],
  "event_types": ["SOFTWARE_RELEASE"],
  "semantic": { "description": "Compose-related changes" },
  "notification_policy": { "mode": "IMMEDIATE" },
  "uncertainties": []
}
```

The proposal is presented to the user for review and confirmation. AI must not
silently create overly broad or ambiguous monitoring rules.

## 9. No AI, no problem

The system is fully usable without any provider configured. AI is a gradual
enhancement introduced in Phase 10.