# Trace — Source Adapter Architecture

> Phase 0 contract. Adapters implement a stable contract so new providers can
> be added without touching the core pipeline (ADR-005).

## Principles

1. A source is monitored as an **exact endpoint**, never silently broadened
   (e.g. a releases endpoint does not become the whole repository or provider).
2. Prefer structured data: API > webhook > feed (RSS/Atom) > JSON-LD > generic
   HTML. Generic web extraction is a **degraded adapter**.
3. All external input is untrusted (Rule 3). Every payload is validated before
   it becomes evidence.
4. Adapters are stateless regarding evidence; deduplication and idempotency
   live in the pipeline using stable keys.
5. Every adapter exposes capability and health information.

## Adapter contract

```text
SourceAdapter
├── id: String                       # e.g. "github"
├── validateEndpoint(endpoint)       # exact-resource check
├── discover(endpoint)               # find structured alternatives
├── fetch(endpoint, cursor)          # → FetchResult(items, nextCursor, etag…)
├── normalize(item)                  # → NormalizedItem (validated, typed)
├── getCapabilities(endpoint)        # e.g. [RELEASES, CONDITIONAL_POLL]
├── getCursor(endpoint)              # persistence resume point
├── advanceCursor(endpoint, result)
└── healthCheck(endpoint)            # → SourceHealth
```

`NormalizedItem` (before evidence insertion) carries:

```text
external_id, canonical_url, title, author, published_at, content (compact),
content_hash, language, metadata
```

The pipeline turns `NormalizedItem` into `Evidence` using the idempotency key
`source_id + external_id` (Rule 4).

## Source resolution flow

```text
URL
 ↓ normalize (scheme, host casing, trailing slash, query stripping where unsafe)
 ↓ detect provider
 ↓ recognize endpoint (exact resource)
 ↓ detect structured alternatives (RSS/Atom/JSON-LD/sitemap)
 ↓ resolve exact resource
 ↓ determine capabilities
 ↓ select adapter
 ↓ present preview for user confirmation (never silently broaden)
```

Example:

```text
https://github.com/androidx/androidx/releases
 → provider=GITHUB, resource=REPOSITORY, owner=androidx, repository=androidx
 → endpoint=github://androidx/androidx/releases
 → capabilities=[RELEASES]
 → adapter=github
```

If a generic website exposes RSS, prefer the structured RSS source over HTML
scraping after explicit user confirmation or clear source-resolution UI.

## Generic web strategy (degraded adapter)

For arbitrary user URLs (see plan §8):

1. Validate URL; enforce HTTPS unless HTTP is explicitly required and allowed.
2. Check robots policy where applicable (implemented with generic-web
   extraction in Phase 13; resolution probing currently fetches only pages a
   user explicitly submits).
3. Detect RSS → Atom → sitemaps → JSON-LD → structured metadata → canonical URL.
4. Fall back to HTML extraction only when required, with strict resource limits.
5. Record parser and source health. Never assume arbitrary HTML is stable.

## Adapters

| Adapter | Endpoint identity | Capabilities (examples) | Status |
|---------|-------------------|-------------------------|--------|
| `github` | `github://owner/repo/kind` (kind ∈ releases, tags) | `RELEASES`, `CONDITIONAL_POLL`, `PAGINATED`, `RATE_LIMITED_API` | **implemented (Phase 2; releases fetchable)** |
| `rss` | feed URL itself | `FEED_ENTRIES`, `CONDITIONAL_POLL` | **implemented (Phase 2)** |
| `atom` | feed URL itself | `FEED_ENTRIES`, `CONDITIONAL_POLL` | **implemented (Phase 2)** |
| `web_page` | page URL | `HTML_EXTRACT` (degraded: health probing + discovery only until Phase 13) | **implemented as degraded fallback (Phase 2)** |
| `json_api` / `rest_api` | URL + endpoint type | provider-defined | future |
| `sec` | `sec://company/{cik}/filings` | `FILINGS` | future |
| `reddit` / `youtube` / `custom` | provider-specific | provider-defined | future |

Only implement the adapters required for the current phase.

### Selection rules (Phase 2 implementation)

- Registration (`POST /api/v1/sources`) carries `endpoint_uri` **plus the
  confirmed `adapter_id`** from resolution. The server validates that the
  adapter exists and explicitly supports the URI — client input is untrusted.
- Feed URLs and page URLs are indistinguishable by shape alone, so stored
  endpoints remember their adapter id; fetching always selects by that stored
  id (`endpoints.adapter` is authoritative).
- All attributes shown to clients (provider, type, capabilities, trust) are
  derived server-side from the adapter — never echoed from request bodies.

## Fetch constraints (all adapters)

Bounded by rule: max response size (default 2 MB, web pages 1 MB), max feed
entries per cycle (feeds 100, GitHub 30/page), max redirects (3), timeouts
(connect 5s / total 15s), conditional requests (ETag/If-None-Match), and
provider rate limits. See SECURITY.md §4 (SSRF) and plan §11.

### Transport security (implemented, ADR-011)

The `SafeHttpFetcher` (infrastructure/fetch) is the only outbound path:
HTTPS-only by default; every hop's host is DNS-resolved and validated against
private/link-local/metadata ranges **by IP** (IPv4, IPv6, v4-mapped, NAT64);
redirects are followed manually with per-hop revalidation (max 3); response
and decompressed bodies are hard-capped. Full DNS pinning at connect time is
deferred to Phase 14 hardening and tracked in THREAT_MODEL.md T2.

XML parsing of feeds disables DTDs/external entities entirely (XXE-safe).
GitHub JSON is parsed defensively as untrusted input.

## Source health

Per-endpoint state machine (DATA_MODEL.md `source_health`):

```text
HEALTHY → DEGRADED → STALE
HEALTHY → BLOCKED / AUTH_REQUIRED / RATE_LIMITED / PARSER_FAILED / NETWORK_FAILED
ANY → DISABLED
```

Failed sources are visible to the user and observable by operators. Never
silently stop monitoring. `STALE` transitions are computed by the Phase 8
scheduler from `last_item_at` freshness; earlier phases set the failure states
directly.

## Adapter contract tests

Every adapter must pass a common fixture-based contract test suite:

```text
valid endpoint, invalid endpoint, empty response, duplicate response,
rate limit, timeout, malformed payload, pagination, cursor handling
```

Fixtures live next to each adapter module. Contract tests run in CI.

## Capability model

Capabilities are explicit strings, e.g.:

```text
RELEASES, FEED_ENTRIES, FILINGS, CONDITIONAL_POLL, WEBHOOK, HTML_EXTRACT,
RATE_LIMITED_API, PAGINATED
```

The scheduler and pipeline read capabilities to choose polling strategy
(webhook vs conditional poll vs adaptive interval — ADR-009) and to gate what an
endpoint may produce.