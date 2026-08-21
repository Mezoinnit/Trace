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
2. Check robots policy where applicable.
3. Detect RSS → Atom → sitemaps → JSON-LD → structured metadata → canonical URL.
4. Fall back to HTML extraction only when required, with strict resource limits.
5. Record parser and source health. Never assume arbitrary HTML is stable.

## Adapters

| Adapter | Endpoint identity | Capabilities (examples) | Status |
|---------|-------------------|-------------------------|--------|
| `github` | `github://owner/repo/kind` | `RELEASES`, `CONDITIONAL_POLL` | Phase 4 |
| `rss` | `https://…/feed.xml` | `FEED_ENTRIES`, `ETAG` | Phase 2 |
| `atom` | `https://…/feed.atom` | `FEED_ENTRIES`, `ETAG` | Phase 2 |
| `json_api` / `rest_api` | URL + endpoint type | provider-defined | Phase 2 |
| `web_page` | URL | `HTML_EXTRACT` (degraded) | Phase 13 |
| `sec` | `sec://company/{cik}/filings` | `FILINGS` | future |
| `reddit` / `youtube` / `custom` | provider-specific | provider-defined | future |

Only implement the adapters required for the current phase.

## Fetch constraints (all adapters)

Bounded by rule: max response size, max decompressed size, max feed entries per
cycle, max redirects, timeouts, concurrency limits, conditional requests
(ETag/Last-Modified), and provider rate limits. See SECURITY.md §4 (SSRF) and
plan §11.

## Source health

Per-endpoint state machine (DATA_MODEL.md `source_health`):

```text
HEALTHY → DEGRADED → STALE
HEALTHY → BLOCKED / AUTH_REQUIRED / RATE_LIMITED / PARSER_FAILED / NETWORK_FAILED
ANY → DISABLED
```

Failed sources are visible to the user and observable by operators. Never
silently stop monitoring.

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