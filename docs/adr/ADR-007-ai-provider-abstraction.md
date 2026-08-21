# ADR-007: AI Provider Abstraction

**Status:** Accepted  
**Date:** 2026-08-21

## Context

The product can use LLMs for extraction, classification, summarization, and
NL parsing. The domain must never depend on a single provider (Gemini, OpenAI,
Anthropic) and must operate with AI unavailable. Cost must stay controllable.

## Decision

- All AI access goes through an **`AiGateway`** abstraction; the domain never
  imports provider SDKs.
- Providers are configuration-driven and pluggable: `LocalProvider` (V1 dev
  default, $0), plus optional `GeminiProvider`, `OpenAiProvider`,
  `AnthropicProvider`.
- Every AI output is **untrusted structured input**, validated against schemas;
  invalid output falls back to deterministic processing.
- Deterministic processing is preferred; LLMs are not invoked for known
  structured events.
- Decisions record provider/model/tokens for reproducibility (`ai_operations`).

## Consequences

- AI can be added (Phase 10) or swapped without architecture changes.
- System remains fully functional with no provider configured.
- No paid API is a hard dependency for V1 development (Rule 10).

References: plan §2.5, §17, §18, AI.md.