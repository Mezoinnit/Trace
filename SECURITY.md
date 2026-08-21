# Trace — Security Architecture

> Phase 0 contract. Security is a first-class requirement. Detailed attacker
> enumeration and mitigations live in THREAT_MODEL.md.

## 1. Security principles

1. **Zero trust on external input** — URLs, HTTP responses, feeds, AI output,
   and user input are untrusted.
2. **Tenant isolation is mandatory** — `workspace_id` is resolved server-side
   from authenticated identity; never from the client. Cross-tenant reads
   return `404`.
3. **Least privilege** — scoped credentials, minimal secrets, redacted logs.
4. **Defense in depth** — application authorization plus PostgreSQL Row-Level
   Security where supported (ADR-010).
5. **Deterministic over magical** — no LLM is the authority for security,
   authorization, or integrity decisions.

## 2. Network security

- TLS everywhere; HTTPS only by default.
- Strict outbound egress controls for fetch workers; no unrestricted internal
  network access from source fetchers.
- Private network boundaries where supported.

## 3. SSRF protections (fetch workers)

Block (by resolved IP, not by URL string only):

```text
127.0.0.0/8       10.0.0.0/8        172.16.0.0/12    192.168.0.0/16
169.254.0.0/16    ::1               fc00::/7          fe80::/10
```

Plus cloud metadata endpoints (e.g. `169.254.169.254`).

Procedure for every outbound fetch:

1. Allow only approved protocols (`https`, `http` only when explicitly allowed).
2. Block localhost/private/link-local/metadata ranges.
3. Resolve DNS and validate the resulting IP (mitigate DNS rebinding).
4. Revalidate the destination after every redirect.
5. Limit redirect count (default 3).
6. Enforce connection and total request timeouts.
7. Bound response size and decompressed size.

The fetcher runs in a restricted worker environment where practical.

## 4. Input validation

Validate: URLs, endpoint types, request parameters, query sizes, Watch
complexity, AI outputs, and external identifiers. Never trust client-provided
enums or IDs without server validation.

## 5. Rate limiting

Limits at: IP, account, workspace, endpoint, source, and expensive operation.
Especially protected: source resolution, generic web fetching, AI operations,
search, Watch creation, authentication. Over-limit → `429` + `Retry-After`.

## 6. Resource limits

Max request body, max response body, max decompressed body, max HTML document,
max feed entries per cycle, max redirects, max execution time, max concurrent
fetches, max AI tokens, max Watch complexity. See DATA_MODEL / WATCH_MODEL bounds.

## 7. Authentication and sessions

- Use a proven provider/implementation; no custom password auth unless necessary.
- Short-lived access tokens, refresh rotation, session/device management,
  account deletion, tenant membership checks, rate limiting, audit events.
- Never log tokens, API keys, passwords, or credentials.

## 8. Secrets

- No secrets in source code. Environment variables for local dev; encrypted
  secrets in deployment; rotation; scoped credentials.
- Provider credentials never appear in the Android app. The app communicates
  only with the backend.
- Local secrets in `backend/.env` (gitignored); placeholders in
  `backend/.env.example`.

## 9. Data at rest

- PostgreSQL as source of truth with proper constraints and transactions.
- Source credentials encrypted at rest (`source_credentials.encrypted_secret`).
- Raw payloads in object storage are access-controlled; public content is not
  republished wholesale (copyright posture, plan §12).

## 10. Logging and observability

- Structured logs; never log secrets or raw credentials; sensitive fields
  redacted (OBSERVABILITY.md).
- Correlation IDs thread through `source → evidence → event → watch → alert →
  notification` for investigations.

## 11. Deployment hardening (Phase 14)

- Secure headers, dependency scanning, static analysis, backup/restore, DR,
  monitoring, alerting, incident procedures.
- Mandatory security tests: IDOR, cross-tenant access, SSRF, DNS rebinding,
  oversized payloads, redirect abuse, malformed content, auth abuse,
  rate-limit bypass, duplicate notification, job replay, data corruption.

## 12. Security response

All findings are documented and triaged via the repository's issue tracker.
Secrets exposure requires immediate rotation and a postmortem.