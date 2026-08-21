# Trace — Threat Model

> Phase 0 contract. Enumerates the primary threats to the platform and the
> controls that mitigate them. This file is updated whenever the architecture
> or trust boundaries change.

## Trust boundaries

```text
TRUSTED      Untrusted external input
──────────   ────────────────────────
API / Auth     Client-supplied workspace_id, enums, IDs, bodies
Domain         User-provided URLs and endpoint definitions
PostgreSQL     HTTP responses from any monitored source (feeds, APIs, HTML)
Deployment     AI provider output
               Arbitrary redirect chains
```

## Assets

| asset | sensitivity |
|-------|-------------|
| tenant data (sources, watches, alerts) | high — isolation mandatory |
| evidence / intelligence | high — integrity, traceability |
| source credentials | high — encrypted at rest, scoped |
| auth tokens / refresh tokens | high — never logged |
| AI provider keys | high — server-side only |

## Threats and mitigations

### T1 — Tenant isolation bypass / IDOR

- Attacker accesses another workspace's watches, events, or sources via crafted
  IDs.
- Controls: server-side tenant context from identity (never client input);
  cross-tenant reads return `404`; every repository method enforces tenant
  context; RLS as defense-in-depth (ADR-010); mandatory IDOR and cross-tenant
  tests (Phase 14).

### T2 — SSRF and DNS rebinding

- Attacker supplies a URL that resolves to internal/metadata endpoints; rebinding
  swaps DNS between validation and fetch.
- Controls: protocol allowlist; block private/link-local/metadata ranges by
  resolved IP; DNS validation; revalidation after redirects; bounded redirects;
  restricted egress; timeouts; size caps (SECURITY.md §3).

### T3 — Resource exhaustion

- Unbounded response bodies, feed sizes, redirects, concurrent fetches, AI
  tokens, search queries.
- Controls: hard limits at every layer (SECURITY.md §6), backpressure, bounded
  concurrency, cursor pagination, queue depth monitoring (ARCHITECTURE.md §15).

### T4 — Authentication abuse

- Token theft, brute force, refresh rotation abuse, replay.
- Controls: proven auth implementation, short-lived tokens, rotation, device
  session management, rate limiting, audit events, never-logging secrets.

### T5 — Data integrity loss (silent mutation)

- Overwriting historical evidence or merging contradictory events.
- Controls: immutable evidence + `evidence_versions`, `CONTESTED` state,
  event correction/versioning, audit records (Rule 5, EVENT_MODEL.md).

### T6 — Alert/notification duplication or forgery

- Duplicate alerts from retried jobs; spoofed notifications.
- Controls: idempotency keys `(watch_id, event_id)` and `(alert_id, device_id)`;
  unique constraints; delivery idempotency (ADR-012); job idempotency.

### T7 — Malicious content (stored XSS in clients)

- Attacker-controlled titles/summaries rendered in Android.
- Controls: treat all source content as untrusted; sanitize before parsing;
  never render untrusted HTML in privileged contexts; Compose renders text, not
  HTML; server caps content sizes.

### T8 — Malformed AI output

- LLM returns invalid structured data used for decisions.
- Controls: strict schema validation; reject and fall back to deterministic
  processing; AI never the sole authority for identity/security/idempotency
  (AI.md).

### T9 — Credential / secret exposure

- Leaked provider keys or DB passwords.
- Controls: no secrets in source; env/encrypted storage; rotation; redacted
  logs; `.env` gitignored; Android holds no provider credentials.

### T10 — Rate-limit bypass

- Distributed or rotating abuse of expensive endpoints.
- Controls: layered limits (IP, account, workspace, endpoint, source), plus
  per-expensive-operation caps; Phase 14 verification.

### T11 — Job replay / corruption

- Re-running jobs duplicates side effects or corrupts state.
- Controls: idempotent job handlers, unique idempotency keys, typed payloads,
  dead-letter state with recorded failure (Rule 5), concurrency limits.

### T12 — DNS rebinding / mis-routed fetches at scale

- See T2. Additionally, fetchers run in a restricted worker environment.

## Verification plan (Phase 14)

Automated security tests for: IDOR, cross-tenant access, SSRF, DNS rebinding,
oversized payloads, redirect abuse, malformed content, auth abuse, rate-limit
bypass, duplicate notification, job replay, data corruption. Each phase also
runs a security review before Definition of Done.