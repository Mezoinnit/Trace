# Targeted Intelligence Platform — Master Implementation Plan

## 0. Document Purpose

This document is the authoritative implementation blueprint for a cloud-based, multi-tenant intelligence monitoring platform.

The product is **not a conventional news aggregator**.

Its purpose is to let a user:

- identify an exact source or endpoint;
- identify a specific entity, topic, event, or market instrument;
- create persistent monitoring rules;
- continuously ingest new information from trusted or user-specified sources;
- normalize raw information into evidence;
- resolve evidence into events and entities;
- deduplicate multiple reports of the same event;
- evaluate relevance, significance, confidence, and source reliability;
- maintain historical timelines;
- search and investigate accumulated intelligence;
- receive Android push notifications when monitored conditions are met.

The initial product domain is **Developer Intelligence**.

The long-term product is a **general-purpose intelligence monitoring platform** supporting technology, companies, markets, security, military/defense, science, research, finance, politics, and custom domains.

The implementation must prioritize:

1. correctness;
2. source traceability;
3. security;
4. tenant isolation;
5. deterministic behavior;
6. observability;
7. predictable performance;
8. cost efficiency;
9. extensibility;
10. graceful degradation.

Do not optimize for feature count at the expense of these properties.

---

# 1. Product Definition

## 1.1 Core Product Statement

> A user-controlled intelligence platform that continuously monitors exactly what the user cares about, from exactly the sources they choose, converts raw information into structured events and entities, preserves historical context, and alerts the user when meaningful changes occur.

The core lifecycle is:

```text
SOURCE
  ↓
EVIDENCE
  ↓
EVENT
  ↓
ENTITY
  ↓
INTELLIGENCE
  ↓
WATCH
  ↓
ALERT
```

The system must also support investigation:

```text
USER QUERY
  ↓
QUERY INTERPRETATION
  ↓
ENTITY / EVENT / SOURCE RESOLUTION
  ↓
SEARCH
  ↓
TIMELINE / EVIDENCE / RELATIONSHIPS
```

---

# 2. Product Principles

## 2.1 Do Not Build a Typical News Feed

The primary objects are not articles.

The primary objects are:

- Sources
- Evidence
- Events
- Entities
- Watches
- Alerts
- Timelines
- Investigations

An article is evidence for an event.

Example:

```text
Google announcement
GitHub release
Documentation update
Third-party article
```

may all represent:

```text
ONE EVENT
```

This is a fundamental architectural decision.

---

## 2.2 Event-Centric Model

Example:

```text
EVENT
type: SOFTWARE_RELEASE
entity: Jetpack Compose
version: 1.9.0
actor: AndroidX
timestamp: ...
confidence: HIGH
```

Evidence:

```text
Evidence A → official announcement
Evidence B → GitHub release
Evidence C → official changelog
Evidence D → third-party article
```

The UI should present one event with multiple supporting sources rather than four duplicate news cards.

---

## 2.3 User-Controlled Sources

Users can add sources through:

### URL-first flow

```text
Paste URL
    ↓
Source Resolver
    ↓
Provider Detection
    ↓
Endpoint Recognition
    ↓
Capability Detection
```

### Provider Explorer

```text
Add Source
├── GitHub
│   └── Repository
│       └── Releases
├── RSS
│   └── Feed URL
├── Atom
│   └── Feed URL
├── SEC
│   └── Company
│       └── Filings
└── Website
    └── URL
```

Both flows must result in an **exact endpoint**.

The system must not silently broaden a source from an exact endpoint to an entire provider.

---

## 2.4 Source Trust Is Separate From Event Confidence

A source may be:

- first-party;
- official;
- established publisher;
- community;
- unknown;
- untrusted.

This is **source trust**.

Event confidence is a separate assessment based on evidence:

- corroboration;
- source quality;
- consistency;
- extraction confidence;
- temporal consistency;
- contradiction detection;
- event-specific validation.

Never treat:

```text
official source = guaranteed truth
```

and never treat:

```text unknown source = automatically false
```

---

## 2.5 AI Is an Intelligence Layer, Not the System of Record

The core domain must remain deterministic and auditable.

The domain must never depend directly on:

- Gemini;
- OpenAI;
- Anthropic;
- any single LLM provider.

Use an abstraction:

```text
AiGateway
├── GeminiProvider
├── OpenAiProvider
├── AnthropicProvider
└── LocalProvider
```

The system must remain operational when AI is unavailable.

Use deterministic processing whenever possible.

Do not send every piece of evidence to an LLM.

---

# 3. Target User Experience

## 3.1 First-Use Experience

The main action is:

```text
What do you want to keep an eye on?

[ Search anything... ]
```

Examples:

```text
New AndroidX releases

Security updates for Kotlin

Everything new from OpenAI

New releases in androidx/androidx
```

The system interprets the request and presents a structured preview before creating a persistent Watch.

---

## 3.2 Watch Creation

Natural language:

> Monitor new releases in androidx/androidx and notify me when Compose changes.

The system resolves this into:

```text
Source:
GitHub / androidx/androidx / releases

Entity:
Jetpack Compose

Event:
Software Release

Semantic filter:
Compose-related changes

Notification:
Immediate
```

The user must be able to review and confirm the interpretation.

AI must not silently create overly broad or ambiguous monitoring rules.

---

## 3.3 Home

The home screen is not "Top News".

It is:

```text
YOUR INTELLIGENCE

New since last visit
--------------------

AndroidX
New release

Kotlin
Compiler update

GitHub
3 watched repositories changed

--------------------

WATCHES

Android
2 new

Security
4 new

Markets
1 new
```

---

## 3.4 Search / Investigation

Examples:

> What changed in Kotlin this month?

> Show me everything Google officially announced about Android 17.

> Did NVIDIA have any major events before yesterday's price movement?

The system searches structured intelligence:

- entities;
- events;
- evidence;
- sources;
- timelines;
- market data when available.

It must not simply behave as a conventional web search engine.

---

## 3.5 Event Detail

An event should expose:

```text
Event title
Event type
Timestamp
Confidence
What happened
Why it matters
Supporting evidence
Sources
Related entities
Related events
Timeline position
Contradictions, if any
```

Example:

```text
Compose 1.9 Released

HIGH CONFIDENCE

WHAT HAPPENED
...

WHY IT MATTERS
...

SOURCES
✓ Official
✓ GitHub
✓ Documentation

RELATED EVENTS
...

TIMELINE
...

ENTITIES
...
```

---

# 4. Core Domain Model

## 4.1 User

A person using the system.

Do not place intelligence ownership directly on a user if the system is intended to support future teams/workspaces.

---

## 4.2 Workspace

The tenant boundary.

All user-owned resources must belong to a workspace.

```text
Workspace
├── Members
├── Sources
├── Watches
├── Alerts
├── Notifications
└── Preferences
```

Every tenant-owned database record must have a `workspace_id`.

Tenant isolation is mandatory.

---

## 4.3 Source

Represents a logical information source.

Fields should include:

- id;
- provider;
- source type;
- canonical identity;
- display name;
- trust classification;
- verification state;
- capabilities;
- status;
- health information;
- created_at;
- updated_at.

---

## 4.4 Source Endpoint

Represents the exact monitored resource.

Examples:

```text
github://repository/androidx/androidx/releases
```

```text
https://example.com/feed.xml
```

```text
sec://company/NVIDIA/filings
```

The endpoint must be uniquely identifiable.

Do not rely on a display URL as the only identity.

---

## 4.5 Source Credential

Credentials must never be stored in plaintext application tables.

Use:

- encrypted secrets;
- strict access control;
- rotation;
- minimum required scopes;
- redacted logging.

For V1, support public sources without credentials wherever possible.

---

## 4.6 Evidence

Evidence is immutable or versioned source-derived information.

Suggested fields:

```text
id
source_id
endpoint_id
external_id
canonical_url
title
author
published_at
discovered_at
content
content_hash
raw_payload_reference
metadata
language
created_at
```

Evidence should preserve enough information to reconstruct how an event was derived.

Do not use mutable source content as the historical record.

---

## 4.7 Evidence Version

When a source changes previously published content, preserve versions where necessary.

```text
Evidence
└── EvidenceVersion
    ├── version 1
    ├── version 2
    └── ...
```

This supports auditability and historical analysis.

---

## 4.8 Event

Represents something that happened.

Examples:

```text
SOFTWARE_RELEASE
SECURITY_ADVISORY
PRODUCT_LAUNCH
ANNOUNCEMENT
ARTICLE_PUBLISHED
EARNINGS
SEC_FILING
ACQUISITION
CONTRACT
DELIVERY
POLICY_CHANGE
RESEARCH_PUBLICATION
```

Fields should include:

```text
id
type
title
summary
occurred_at
discovered_at
confidence
significance
status
created_at
updated_at
```

---

## 4.9 Event-Evidence

Many-to-many relationship.

```text
Event
  ↕
Evidence
```

One event may have many evidence records.

One evidence record may contribute to one or more related events if the source covers multiple developments.

---

## 4.10 Entity

An entity is a real or conceptual thing being monitored or referenced.

Types:

```text
COMPANY
PERSON
PRODUCT
PROJECT
SOFTWARE
REPOSITORY
STOCK
CRYPTO_ASSET
COUNTRY
MILITARY_SYSTEM
ORGANIZATION
RESEARCH_TOPIC
PLACE
CUSTOM
```

Fields:

```text
id
type
canonical_name
description
created_at
updated_at
```

---

## 4.11 Entity Alias

Examples:

```text
NVIDIA
Nvidia
NVIDIA Corporation
NVDA
```

Aliases must resolve to the same entity where appropriate.

---

## 4.12 Entity Identifier

Structured external identifiers:

```text
GitHub repository
SEC CIK
Ticker
DOI
ISIN
CUSIP
Package identifier
URL
```

Do not use names alone for entity identity.

---

## 4.13 Entity Relationship

Examples:

```text
NVIDIA
  └── owns → CUDA

NVIDIA
  └── listed_as → NVDA

Google
  └── develops → Android

AndroidX
  └── contains → Jetpack Compose
```

Start with relational tables in PostgreSQL.

Do not introduce a dedicated graph database in V1.

---

## 4.14 Watch

A Watch is a persistent monitoring intent.

A Watch can contain:

- source constraints;
- entity constraints;
- event types;
- keyword/semantic filters;
- exclusions;
- time constraints;
- significance thresholds;
- confidence thresholds;
- notification policy.

Example:

```text
Watch:
AndroidX Releases

Sources:
GitHub / androidx/androidx / releases

Entities:
AndroidX
Jetpack Compose

Events:
SOFTWARE_RELEASE

Filters:
Compose
Stable

Notifications:
Immediate
```

---

## 4.15 Alert

An Alert is created when an Event matches a Watch.

Pipeline:

```text
New Event
  ↓
Watch matching
  ↓
Relevance
  ↓
Significance
  ↓
Confidence
  ↓
Alert
```

Alerts must be idempotent.

The same event must not produce duplicate alerts for the same Watch unless the Watch policy explicitly permits it.

---

## 4.16 Notification

An Alert may produce one or more notifications.

V1:

- Android push via FCM;
- in-app notifications.

Future:

- email;
- Telegram;
- Discord;
- web push;
- other integrations.

---

# 5. Event Processing Pipeline

The authoritative ingestion pipeline is:

```text
SOURCE
  ↓
FETCH
  ↓
VALIDATE
  ↓
NORMALIZE
  ↓
DEDUPLICATE RAW EVIDENCE
  ↓
CLASSIFY
  ↓
EXTRACT ENTITIES
  ↓
EXTRACT EVENT
  ↓
RESOLVE ENTITIES
  ↓
CLUSTER / DEDUP EVENTS
  ↓
CORROBORATE
  ↓
DETECT CONTRADICTIONS
  ↓
CALCULATE CONFIDENCE
  ↓
CALCULATE SIGNIFICANCE
  ↓
INDEX
  ↓
MATCH WATCHES
  ↓
CREATE ALERTS
  ↓
SEND NOTIFICATIONS
```

Every stage must be observable.

---

# 6. Source Adapter Architecture

Define a stable adapter contract.

Conceptually:

```text
SourceAdapter
├── validateEndpoint()
├── discover()
├── fetch()
├── normalize()
├── getCapabilities()
├── getCursor()
├── advanceCursor()
└── healthCheck()
```

Adapters:

```text
GitHubAdapter
RssAdapter
AtomAdapter
JsonApiAdapter
RestApiAdapter
WebPageAdapter
SecAdapter
RedditAdapter
YoutubeAdapter
CustomAdapter
```

Only implement the adapters required for the current phase.

---

# 7. Source Resolution

When a user provides a URL:

```text
URL
 ↓
Normalize URL
 ↓
Detect provider
 ↓
Recognize endpoint
 ↓
Detect structured alternatives
 ↓
Resolve exact resource
 ↓
Determine capabilities
 ↓
Select adapter
```

Example:

```text
https://github.com/androidx/androidx/releases
```

becomes:

```text
Provider:
GitHub

Resource:
Repository

Owner:
androidx

Repository:
androidx

Endpoint:
releases

Capabilities:
RELEASES
```

If a generic website exposes RSS, prefer the structured RSS source over HTML scraping after explicit user confirmation or clear source-resolution UI.

---

# 8. Generic Web Source Strategy

For arbitrary user URLs:

1. Validate URL.
2. Enforce HTTPS unless HTTP is explicitly required and allowed.
3. Check robots policy where applicable.
4. Detect RSS.
5. Detect Atom.
6. Detect sitemaps.
7. Detect JSON-LD.
8. Detect structured metadata.
9. Detect canonical URL.
10. Fall back to HTML extraction only when required.
11. Enforce strict resource limits.
12. Record parser and source health.

The system must never assume that arbitrary HTML is stable.

Generic web extraction must be treated as a degraded adapter.

---

# 9. Source Health

Every source endpoint needs health state.

Suggested states:

```text
HEALTHY
DEGRADED
STALE
BLOCKED
AUTH_REQUIRED
RATE_LIMITED
PARSER_FAILED
NETWORK_FAILED
DISABLED
```

Track:

```text
last_success_at
last_failure_at
failure_count
consecutive_failures
average_latency
last_item_at
last_change_at
next_poll_at
```

Never silently stop monitoring.

A failed source must be visible to the user and observable by operators.

---

# 10. Adaptive Monitoring Scheduler

Do not poll all sources at a fixed interval.

Maintain:

```text
next_poll_at
current_interval
minimum_interval
maximum_interval
change_frequency
failure_count
rate_limit_state
```

Examples:

```text
High-frequency source
→ poll more frequently

Low-frequency source
→ poll less frequently

Repeated failures
→ exponential backoff

Rate limited
→ respect provider limits

Webhook-capable source
→ prefer event-driven updates
```

The scheduler must use jitter to prevent synchronized polling spikes.

---

# 11. Fetcher Security

Arbitrary source URLs introduce SSRF and resource exhaustion risks.

The fetcher must:

- allow only approved protocols;
- block localhost;
- block private IPv4 ranges;
- block private IPv6 ranges;
- block loopback;
- block link-local addresses;
- block cloud metadata endpoints;
- validate DNS resolution;
- revalidate destination after redirects;
- limit redirect count;
- limit response size;
- limit decompression size;
- enforce connection timeout;
- enforce total request timeout;
- limit concurrent connections per tenant/source;
- limit response body processing;
- sanitize content before parsing;
- never execute returned JavaScript;
- never render untrusted HTML in privileged contexts.

DNS rebinding must be considered.

The fetcher should run in a restricted worker environment where practical.

---

# 12. Raw Evidence and Copyright

The platform needs traceability, but must not become an unauthorized content-republishing system.

Store:

- source metadata;
- canonical URL;
- timestamps;
- identifiers;
- extracted facts;
- processing metadata;
- compact evidence where legally appropriate;
- raw payload references when permitted and necessary.

Do not expose full third-party article bodies as a default product feature.

Prefer:

```text
short summary
+
key facts
+
source attribution
+
canonical source link
```

Commercial deployment requires review of source-specific terms and applicable copyright/database rules.

---

# 13. Deduplication

Raw evidence deduplication should use:

```text
canonical URL
external ID
content hash
provider identifier
```

Event deduplication should use multiple signals:

```text
same entities
same event type
temporal proximity
similar facts
semantic similarity
shared identifiers
source relationships
```

Pipeline:

```text
Evidence
 ↓
Raw duplicate detection
 ↓
Candidate event
 ↓
Event similarity
 ↓
Event clustering
 ↓
Existing event or new event
```

Do not use a single similarity threshold as the only decision mechanism.

---

# 14. Contradiction Detection

Conflicting evidence must not be blindly merged.

Example:

```text
Source A:
Contract signed

Source B:
Negotiations ongoing
```

Represent:

```text
Event status:
CONTESTED
```

Maintain:

```text
supporting evidence
contradicting evidence
confidence
```

The UI must expose the disagreement.

---

# 15. Confidence

Confidence is not the same as source trust.

Possible inputs:

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

Store both:

```text
confidence_score
confidence_reasoning
```

Do not make confidence a mysterious LLM-generated number.

The scoring pipeline must be inspectable.

---

# 16. Significance

Not every event deserves an alert.

A significance engine should consider:

```text
event type
entity relevance
watch relevance
novelty
magnitude
source authority
confidence
user preferences
```

Examples:

```text
README typo → low
minor documentation change → low
major release → high
security advisory → very high
critical vulnerability → critical
major acquisition → critical
```

Significance thresholds should be configurable.

---

# 17. AI Usage Rules

AI can be used for:

- entity extraction;
- entity resolution assistance;
- event classification;
- event extraction;
- summarization;
- semantic relevance;
- contradiction analysis;
- natural-language query parsing;
- natural-language Watch creation;
- timeline summaries;
- contextual explanations.

AI must not be the sole authority for:

- source identity;
- exact endpoint identity;
- authorization;
- tenant isolation;
- security decisions;
- billing decisions;
- database integrity;
- notification idempotency;
- final event identity where deterministic validation is possible.

Every AI output must be treated as untrusted structured input and validated against schemas.

---

# 18. AI Provider Abstraction

Define:

```text
AiGateway
```

Capabilities:

```text
classifyEvent()
extractEntities()
resolveEntity()
summarizeEvidence()
summarizeEvent()
parseWatchIntent()
parseInvestigationQuery()
detectContradiction()
```

Providers:

```text
GeminiProvider
OpenAiProvider
AnthropicProvider
LocalProvider
```

Provider selection should be configuration-driven.

The domain must not import provider SDKs.

---

# 19. Cost Control

The product must be designed for zero-cost development and minimal cost at early production scale.

Rules:

1. Prefer deterministic parsing.
2. Prefer provider APIs over scraping where available.
3. Use webhooks when available.
4. Cache aggressively where valid.
5. Use conditional HTTP requests:
   - ETag;
   - Last-Modified.
6. Deduplicate before expensive processing.
7. Do not embed every document.
8. Do not invoke LLMs for known structured events.
9. Batch compatible processing.
10. Apply per-tenant quotas.
11. Apply per-source quotas.
12. Apply provider rate limits.
13. Implement backpressure.
14. Store raw data in object storage when scale requires it.
15. Keep AI provider abstraction so paid services can be added later without architecture changes.

The initial system should support local AI development where practical.

No paid API may be a hard dependency for V1 development.

---

# 20. Backend Architecture

Do not start with microservices.

Use:

> Modular monolith + asynchronous workers.

Logical modules:

```text
auth
tenancy
sources
ingestion
evidence
entities
events
intelligence
watches
alerts
notifications
search
ai
scheduler
market
observability
```

Deployable units can initially be:

```text
API
Worker
Scheduler
```

They may share the same codebase and domain modules.

Split into independent services only when actual scale or isolation requirements justify it.

---

# 21. Backend Technology

Recommended:

```text
Language:
Kotlin

Framework:
Ktor

Database:
PostgreSQL

Search:
PostgreSQL FTS + pg_trgm

Vector:
pgvector

Queue:
PostgreSQL-backed job queue initially

Object Storage:
S3-compatible storage

Push:
Firebase Cloud Messaging

Edge / lightweight infrastructure:
Cloudflare-compatible components where useful
```

Avoid introducing:

- Kubernetes;
- Kafka;
- Elasticsearch;
- Redis;
- dedicated graph databases;

until justified by actual scale.

---

# 22. Job System

Use durable asynchronous jobs.

Suggested fields:

```text
id
type
payload
priority
status
attempts
max_attempts
available_at
locked_at
locked_by
completed_at
failed_at
error_code
error_message
created_at
```

Jobs must support:

- retries;
- exponential backoff;
- dead-letter state;
- idempotency;
- visibility timeout;
- concurrency limits.

Examples:

```text
FETCH_SOURCE
PARSE_SOURCE
NORMALIZE_EVIDENCE
CLASSIFY_EVENT
RESOLVE_ENTITY
MERGE_EVENT
MATCH_WATCHES
CREATE_ALERT
SEND_NOTIFICATION
REINDEX_EVENT
GENERATE_SUMMARY
```

---

# 23. Idempotency

Every externally-triggered operation that can be retried must be idempotent.

Examples:

```text
source fetch
evidence insertion
event creation
watch matching
alert creation
FCM notification dispatch
```

Use stable idempotency keys.

Example:

```text
source_id + external_id
```

for evidence.

And:

```text
watch_id + event_id
```

for alert uniqueness.

---

# 24. Database Principles

Use PostgreSQL as the source of truth.

Requirements:

- foreign keys;
- unique constraints;
- check constraints;
- indexes based on actual query patterns;
- explicit timestamps;
- UTC storage;
- migrations;
- transaction boundaries;
- optimistic locking where appropriate.

Never use destructive production migrations.

Never use:

```text
fallbackToDestructiveMigration
```

in production equivalents.

---

# 25. Tenant Isolation

Every tenant-owned table must have:

```text
workspace_id
```

Application-level authorization is mandatory.

Prefer database-level defense-in-depth using PostgreSQL Row-Level Security where the deployment model supports it.

Never trust:

```text workspace_id
```

supplied by the client.

Resolve tenant membership from authenticated identity.

Every repository/query method must enforce tenant context.

---

# 26. Authentication

Use a proven authentication provider or well-tested authentication implementation.

Do not build custom password authentication unless necessary.

Requirements:

- secure token handling;
- token expiry;
- refresh rotation where applicable;
- device/session management;
- account deletion;
- tenant membership checks;
- rate limiting;
- audit events.

Never log:

- access tokens;
- refresh tokens;
- API keys;
- credentials.

---

# 27. API Design

Use versioned APIs:

```text
/api/v1/...
```

Resource-oriented endpoints.

Examples:

```text
GET    /api/v1/watches
POST   /api/v1/watches
GET    /api/v1/watches/{id}
PATCH  /api/v1/watches/{id}
DELETE /api/v1/watches/{id}

GET    /api/v1/sources
POST   /api/v1/sources/resolve
POST   /api/v1/sources
GET    /api/v1/sources/{id}

GET    /api/v1/events
GET    /api/v1/events/{id}

GET    /api/v1/entities/{id}

POST   /api/v1/investigations

GET    /api/v1/notifications
```

Do not expose internal database structures directly.

---

# 28. Pagination

All collection endpoints must use deterministic pagination.

Prefer cursor pagination for event feeds and large collections.

Avoid unbounded:

```text
SELECT * FROM events
```

Use stable sort keys:

```text
occurred_at DESC, id DESC
```

and cursor values derived from those keys.

---

# 29. Search

V1 search:

```text
PostgreSQL FTS
+
pg_trgm
```

Search indexes should cover:

- event titles;
- summaries;
- entity names;
- aliases;
- evidence titles;
- relevant extracted text.

Later, if actual search requirements exceed PostgreSQL capabilities:

```text
OpenSearch
```

Do not add it preemptively.

---

# 30. Android Architecture

Use Kotlin + Jetpack Compose.

Recommended structure:

```text
:app

:core:model
:core:common
:core:network
:core:database
:core:designsystem
:core:auth
:core:notifications

:feature:home
:feature:search
:feature:watches
:feature:sources
:feature:events
:feature:entities
:feature:timeline
:feature:settings
```

Use:

- Clean Architecture;
- MVVM or unidirectional state flow;
- Coroutines;
- Flow;
- Hilt;
- Retrofit/Ktor client as appropriate;
- Room only for client-side cache/offline state.

The backend remains the source of truth.

---

# 31. Android Offline Strategy

The product is cloud-first, but the app should gracefully handle network loss.

Cache:

- recent events;
- watches;
- entities;
- notifications;
- source summaries.

The UI should clearly distinguish:

```text
cached
syncing
current
offline
```

Never imply that cached intelligence is real-time.

---

# 32. Notification Architecture

V1:

```text
Backend
 ↓
Alert
 ↓
Notification Dispatcher
 ↓
FCM
 ↓
Android
```

Store:

```text
device_id
push_token
platform
app_version
last_seen_at
notification_preferences
```

FCM tokens can rotate. Update them safely.

Never treat a token as permanent identity.

---

# 33. Notification Deduplication

Unique constraint:

```text
watch_id + event_id
```

should prevent duplicate alert creation.

Notification delivery itself should also use an idempotency key.

Example:

```text
alert_id + device_id
```

Retries must not create uncontrolled duplicate notifications.

---

# 34. Shared Intelligence

The architecture should separate:

### Global intelligence

```text
sources
entities
evidence
events
```

from:

### User-specific state

```text
watches
subscriptions
alerts
notification preferences
saved investigations
```

If ten thousand users watch:

```text
GitHub / androidx/androidx / releases
```

the ingestion pipeline should ideally fetch the source once and fan out the resulting event to all matching Watches.

Do not perform ten thousand identical source fetches.

---

# 35. Public / Shared Watches

Long-term:

```text
Watch Definition
       ↓
Subscriptions
       ↓
Users
```

Example:

```text
AndroidX Releases
Kotlin Releases
OpenAI Announcements
NVIDIA Filings
```

A public Watch should be versioned.

Users subscribe to a version or current definition according to product policy.

Do not let a Watch creator silently change what existing subscribers are monitoring.

---

# 36. Market Intelligence

Markets are **not part of V1**.

Architecture must allow them later.

Separate:

```text
Market Data
├── Instrument
├── Price
├── OHLC
├── Volume
├── Market Snapshot
└── Corporate Action
```

from:

```text
Intelligence
├── News
├── Filings
├── Earnings
├── Announcements
└── Events
```

Then correlate them.

Example future investigation:

> What major events occurred around NVIDIA's last 10 significant price movements?

This should be possible without redesigning the event model.

---

# 37. Future Domains

The same engine should eventually support:

```text
Developer Intelligence
Company Intelligence
Market Intelligence
Security Intelligence
Military/Defense Intelligence
Research Intelligence
Science Intelligence
Product Intelligence
Political Intelligence
Custom Intelligence
```

Domains should add:

- entity types;
- event types;
- source adapters;
- validation rules;
- ranking logic;

without replacing the core pipeline.

---

# 38. Security Architecture

Security is a first-class requirement.

## 38.1 Network Security

- TLS everywhere.
- HTTPS only by default.
- strict outbound egress controls for fetch workers.
- private network boundaries where supported.
- no unrestricted internal network access from source fetchers.

## 38.2 SSRF

Block:

```text
127.0.0.0/8
10.0.0.0/8
172.16.0.0/12
192.168.0.0/16
169.254.0.0/16
::1
fc00::/7
fe80::/10
```

and cloud metadata addresses.

Do not rely only on URL string checks.

Resolve DNS and validate the resulting IP.

Revalidate after redirects.

## 38.3 Input Validation

Validate:

- URL;
- endpoint type;
- request parameters;
- query size;
- Watch complexity;
- AI outputs;
- external identifiers.

Never trust client-provided enums or IDs without server validation.

## 38.4 Rate Limiting

Apply limits at:

- IP;
- account;
- workspace;
- endpoint;
- source;
- expensive operation.

Especially protect:

```text
source resolution
generic web fetching
AI operations
search
investigations
Watch creation
authentication
```

## 38.5 Resource Limits

Enforce:

- maximum request body;
- maximum response body;
- maximum decompressed body;
- maximum HTML document size;
- maximum feed entries processed per cycle;
- maximum redirects;
- maximum execution time;
- maximum concurrent fetches;
- maximum AI tokens;
- maximum Watch complexity.

---

# 39. Secrets

Never store secrets in source code.

Use:

- environment variables for local development;
- encrypted secrets in deployment;
- secret rotation;
- scoped credentials.

Never put provider credentials in Android.

The Android app must communicate only with the backend.

---

# 40. Logging

Logs must be structured.

Include:

```text
timestamp
level
service
environment
correlation_id
request_id
workspace_id when safe
source_id when safe
event_id when safe
operation
latency
result
```

Never log:

- access tokens;
- API keys;
- passwords;
- raw credentials;
- private content unnecessarily.

Sensitive fields must be redacted.

---

# 41. Observability

Metrics:

### Source

```text
fetch_success_rate
fetch_failure_rate
fetch_latency
parser_success_rate
source_freshness
rate_limit_events
```

### Intelligence

```text
evidence_created
event_created
event_merged
duplicate_rate
contradiction_rate
entity_resolution_confidence
```

### Watches

```text
watch_matches
alerts_created
false_positive_feedback
```

### Notifications

```text
notification_queued
notification_sent
notification_failed
notification_opened
```

### AI

```text
requests
latency
failures
tokens
provider
cost if applicable
```

---

# 42. Tracing

Every processing chain should be traceable:

```text
source fetch
 ↓
evidence
 ↓
event
 ↓
watch match
 ↓
alert
 ↓
notification
```

Use correlation IDs.

A developer should be able to investigate:

> Why did this user receive this notification?

and obtain the exact chain of decisions.

---

# 43. Auditability

Important state changes should create audit records.

Examples:

```text
Watch created
Watch changed
Watch deleted
Source added
Source disabled
Credential changed
Notification preference changed
```

For intelligence:

```text
event created
event merged
event corrected
event status changed
```

Do not silently mutate important historical intelligence.

Prefer versioning/correction records.

---

# 44. Testing Strategy

## Unit Tests

Cover:

- entity resolution;
- event classification;
- Watch matching;
- significance;
- confidence;
- URL normalization;
- endpoint resolution;
- scheduler decisions;
- deduplication;
- idempotency.

## Adapter Contract Tests

Every adapter must satisfy a common test contract:

```text
valid endpoint
invalid endpoint
empty response
duplicate response
rate limit
timeout
malformed payload
pagination
cursor handling
```

Use fixture-based tests.

## Integration Tests

Test:

```text
source → evidence → event → watch → alert
```

as an end-to-end backend flow.

## Security Tests

Test:

- SSRF;
- DNS rebinding scenarios;
- redirect abuse;
- oversized responses;
- authentication bypass;
- tenant isolation;
- IDOR;
- rate-limit bypass;
- malformed AI output;
- malicious HTML;
- credential exposure.

---

# 45. Performance Requirements

The implementation must avoid unbounded work.

Rules:

- never fetch unlimited content;
- never process unlimited feed entries;
- never run unlimited AI requests;
- never return unlimited API results;
- never execute unbounded joins;
- never perform N+1 database queries;
- never load entire histories when a timeline window is sufficient.

Use:

- indexes;
- cursor pagination;
- batch operations;
- caching;
- conditional requests;
- asynchronous processing;
- bounded concurrency;
- connection pooling.

---

# 46. Database Performance

Every major query must be explainable and index-supported.

Before production:

- inspect `EXPLAIN ANALYZE`;
- identify sequential scans;
- test realistic row counts;
- test high-cardinality tenants;
- test event feed queries;
- test Watch matching;
- test source scheduling queries.

Indexes should reflect actual access patterns, not every column.

---

# 47. Backpressure

If ingestion becomes faster than processing:

```text
FETCH
 ↓
QUEUE
 ↓
PROCESS
```

must absorb the difference.

Workers must not overwhelm:

- database;
- AI provider;
- external APIs;
- notification services.

Use bounded concurrency and queue depth monitoring.

---

# 48. Failure Handling

Every external dependency can fail.

The system must distinguish:

```text
temporary failure
permanent failure
authentication failure
rate limit
malformed source
source removed
source changed
internal processing failure
```

Retries should occur only for retryable failures.

Use exponential backoff with jitter.

Do not retry permanent errors indefinitely.

---

# 49. Graceful Degradation

Examples:

### AI unavailable

Still:

- ingest;
- normalize;
- store;
- process structured events;
- notify deterministic Watches.

### One source unavailable

Other sources continue.

### Search unavailable

Core feeds and Watches continue.

### FCM unavailable

Alerts remain queued and visible in-app.

### Generic parser broken

Source becomes degraded; user is informed.

The system must fail partially, not globally.

---

# 50. Development Phases

# Phase 0 — Specification and Repository Foundation

## Goal

Freeze the architecture before feature implementation.

## Deliverables

- repository;
- architecture documentation;
- ADRs;
- domain model;
- database ERD;
- API contract;
- security threat model;
- source adapter contract;
- AI abstraction contract;
- job contract;
- testing strategy;
- coding conventions;
- CI pipeline;
- environment configuration.

## Rules

Do not implement UI features before the domain and API contracts are stable.

## Exit Criteria

- architecture reviewed;
- all core entities defined;
- tenant boundaries documented;
- security model documented;
- migrations strategy defined;
- local development works with no paid dependency.

---

# Phase 1 — Multi-Tenant Backend Core

## Goal

Build a secure backend foundation.

## Implement

- authentication;
- workspaces;
- tenant authorization;
- PostgreSQL;
- migrations;
- API versioning;
- structured logging;
- error model;
- request IDs;
- rate limiting;
- health checks;
- configuration;
- repository layer;
- transaction boundaries.

## Exit Criteria

A user can:

```text
sign in
→ access workspace
→ create/read/update/delete tenant-owned resources
```

and cannot access another tenant's resources.

---

# Phase 2 — Source System

## Goal

Create the generic source abstraction.

## Implement

- Source;
- SourceEndpoint;
- source resolver;
- source health;
- adapter interface;
- URL normalization;
- endpoint identity;
- capability model.

## Initial adapters

1. GitHub
2. RSS
3. Atom
4. Generic JSON/REST
5. Generic Web as a controlled fallback

## Exit Criteria

A user can paste a URL and the backend can:

```text
resolve
validate
identify
select adapter
fetch
normalize
report health
```

---

# Phase 3 — Evidence System

## Goal

Create immutable, traceable source evidence.

## Implement

- evidence schema;
- external IDs;
- canonical URLs;
- content hashes;
- raw payload references;
- evidence versions;
- deduplication;
- source attribution.

## Exit Criteria

The same external item fetched repeatedly does not create duplicate evidence.

The system can explain exactly where an evidence record came from.

---

# Phase 4 — GitHub Vertical Slice

## Goal

Create the first complete end-to-end intelligence workflow.

## Example

```text
androidx/androidx
→ releases
→ new release
→ evidence
→ event
→ Watch
→ alert
→ Android notification
```

## Implement

- repository resolution;
- release endpoint;
- pagination/cursors;
- rate-limit handling;
- conditional polling where supported;
- release normalization;
- release event creation.

## Exit Criteria

A real GitHub repository can be monitored continuously and a new release can produce exactly one correct alert.

---

# Phase 5 — Event Engine

## Goal

Move from source records to structured events.

## Implement

- event types;
- event creation;
- event/evidence relationships;
- event deduplication;
- event clustering;
- confidence;
- significance;
- contradiction state.

## Exit Criteria

Multiple evidence items representing the same event resolve into one event.

Contradictory evidence is preserved rather than silently merged.

---

# Phase 6 — Entity System

## Goal

Build the intelligence identity layer.

## Implement

- entities;
- aliases;
- identifiers;
- relationships;
- entity resolution;
- entity merging;
- entity references from events/evidence.

## Exit Criteria

Different names and identifiers for the same entity resolve consistently.

False merges must be recoverable.

---

# Phase 7 — Watch Engine

## Goal

Build persistent user monitoring.

## Implement

- Watch CRUD;
- Watch rules;
- source filters;
- entity filters;
- event filters;
- keyword filters;
- semantic filters;
- exclusions;
- significance thresholds;
- confidence thresholds;
- match engine.

## Exit Criteria

A Watch matches the correct events and does not repeatedly alert on the same event.

---

# Phase 8 — Scheduler and Adaptive Monitoring

## Goal

Make monitoring continuously operational.

## Implement

- scheduler;
- polling intervals;
- adaptive intervals;
- backoff;
- jitter;
- rate-limit awareness;
- concurrency limits;
- source health;
- job queue;
- retry policy.

## Exit Criteria

The system can monitor many endpoints without synchronized polling spikes or uncontrolled external requests.

---

# Phase 9 — Android Application

## Goal

Expose the core intelligence workflow through Android.

## Screens

```text
Home
Search
Watches
Sources
Event Detail
Entity Detail
Timeline
Notifications
Settings
```

## Implement

- authentication;
- API client;
- local cache;
- Compose UI;
- design system;
- Watch creation;
- source resolution;
- event browsing;
- notification handling.

## Exit Criteria

A user can:

```text
add source
→ create Watch
→ wait for event
→ receive notification
→ open event
→ inspect evidence
→ inspect timeline
```

---

# Phase 10 — AI Intelligence Layer

## Goal

Add AI where it creates real product value.

## Implement

- AI gateway;
- provider abstraction;
- structured output validation;
- entity extraction;
- event extraction;
- summaries;
- semantic relevance;
- contradiction analysis;
- natural-language Watch creation;
- natural-language investigation queries.

## Rules

- AI outputs are untrusted.
- Validate every structured output.
- Enforce token/input limits.
- Cache deterministic AI results where safe.
- Record model/provider/version metadata for reproducibility.
- Allow operation without AI.

## Exit Criteria

A user can say:

> Monitor AndroidX releases and notify me about Compose changes.

and receive a correct structured Watch proposal before confirmation.

---

# Phase 11 — Search and Investigation

## Goal

Turn accumulated events into an intelligence database.

## Implement

- full-text search;
- entity search;
- event search;
- time filtering;
- source filtering;
- event-type filtering;
- relevance ranking;
- investigation queries;
- timeline generation.

## Exit Criteria

Queries such as:

```text
What changed in Kotlin this month?
```

produce structured intelligence rather than a flat article list.

---

# Phase 12 — Historical Intelligence

## Goal

Make history a first-class product feature.

## Implement

- entity timelines;
- event timelines;
- related events;
- source history;
- evidence history;
- event corrections;
- event versioning;
- relationship exploration.

## Exit Criteria

A user can inspect an entity and understand:

```text
what happened
when
from which sources
how confidence changed
what events were related
```

---

# Phase 13 — Generic Web Intelligence

## Goal

Support arbitrary user-provided sources safely.

## Implement

- RSS discovery;
- Atom discovery;
- sitemap discovery;
- JSON-LD;
- structured metadata;
- canonical URL extraction;
- controlled HTML extraction;
- source health;
- parser fallback;
- anti-SSRF controls.

## Exit Criteria

A user can provide a legitimate website and the system can safely discover the best available structured endpoint or use a controlled web adapter.

---

# Phase 14 — Security and Production Hardening

## Goal

Prepare for real users.

## Implement and verify

- SSRF defenses;
- tenant isolation;
- authentication hardening;
- rate limits;
- abuse prevention;
- secret management;
- audit logs;
- encryption;
- secure headers;
- dependency scanning;
- static analysis;
- backup/restore;
- disaster recovery;
- monitoring;
- alerting;
- incident procedures.

## Mandatory tests

- IDOR;
- cross-tenant access;
- SSRF;
- DNS rebinding;
- oversized payloads;
- redirect abuse;
- malformed content;
- auth abuse;
- rate-limit bypass;
- duplicate notification;
- job replay;
- data corruption.

---

# Phase 15 — Cost and Performance Optimization

## Goal

Keep the platform sustainable before introducing paid infrastructure.

## Optimize

- fetch frequency;
- cache hit rate;
- conditional requests;
- batch processing;
- AI calls;
- embeddings;
- database indexes;
- query plans;
- job concurrency;
- object storage;
- source reuse;
- global event reuse.

## Key principle

One source should be ingested once and reused across all Watches and tenants whenever legally and technically appropriate.

---

# Phase 16 — Market Intelligence

Only after the core intelligence engine is stable.

## Implement

- instruments;
- tickers;
- price data;
- OHLC;
- volume;
- corporate actions;
- earnings;
- filings;
- market events;
- market-aware timelines.

Keep market data separate from generic evidence while allowing events to reference instruments.

---

# Phase 17 — Additional Intelligence Domains

Add:

```text
Security
Military/Defense
Research
Science
Companies
Politics
Products
Finance
```

Each domain adds:

- source adapters;
- entity types;
- event types;
- validation;
- ranking;
- UI only where required.

Do not fork the core intelligence engine.

---

# Phase 18 — Shared Watches and Public Intelligence

Implement:

- public Watch definitions;
- versioning;
- subscriptions;
- popularity;
- quality ranking;
- abuse reporting;
- curated source packs;
- domain packs.

Examples:

```text
Android Developer Watch
Kotlin Watch
OpenAI Watch
NVIDIA Watch
Security Watch
```

---

# Phase 19 — Intelligence Graph and Correlation

Long-term capabilities:

- event correlation;
- cross-source corroboration;
- contradiction graphs;
- entity relationships;
- event chains;
- historical pattern detection;
- market/event correlation;
- impact analysis.

Example:

```text
Company
 ↓
Announcement
 ↓
Product
 ↓
Supply chain event
 ↓
Market movement
```

---

# 51. Non-Goals for V1

Do not build:

- generic social feed;
- trending page as primary product;
- comments;
- likes;
- follower graph;
- infinite-scroll dopamine mechanics;
- market trading;
- investment recommendations;
- full public social network;
- custom graph database;
- Kubernetes;
- microservices;
- paid scraping infrastructure;
- mandatory paid AI;
- mandatory paid news APIs.

The product is an intelligence system, not a social network.

---

# 52. Definition of Done

A phase is not complete because code compiles.

A phase is complete only when:

- implementation exists;
- automated tests exist;
- failure cases are covered;
- security implications are addressed;
- metrics exist;
- logs exist;
- migrations exist;
- API behavior is documented;
- tenant isolation is tested;
- performance is acceptable;
- no known critical security issue remains;
- the next phase can consume the resulting contract without rework.

---

# 53. Engineering Rules for the Implementing AI

The implementing AI must follow these rules.

## Rule 1 — Do Not Invent Requirements

If something is not specified, prefer:

1. existing architecture;
2. established domain constraints;
3. simplest safe behavior;
4. documented ADR.

Do not silently introduce major product behavior.

## Rule 2 — Preserve Domain Boundaries

Do not place business logic in:

- controllers;
- Compose screens;
- database models;
- HTTP adapters.

Use application/domain layers.

## Rule 3 — External Systems Are Untrusted

Treat:

- URLs;
- HTTP responses;
- RSS feeds;
- GitHub content;
- AI output;
- user input;

as untrusted.

Validate everything.

## Rule 4 — Idempotency Is Mandatory

Any operation that can be retried must be safe to retry.

## Rule 5 — No Silent Data Loss

Never:

- overwrite historical evidence without versioning;
- silently merge conflicting events;
- silently delete source data;
- drop failed jobs without recording failure.

## Rule 6 — No Unbounded Operations

Every expensive operation needs a bound.

## Rule 7 — No Hidden Background Behavior

Users must understand:

- what they are watching;
- which sources are monitored;
- why an alert was generated;
- when a source is unhealthy.

## Rule 8 — Prefer Structured Data

If a provider exposes an API, feed, webhook, or structured endpoint, prefer it over scraping.

## Rule 9 — AI Must Be Replaceable

Never couple the core system to a single AI provider.

## Rule 10 — Build for $0 Development

Do not introduce paid dependencies unless there is no practical free/open-source alternative and the decision is explicitly documented.

---

# 54. Required Documentation in the Repository

The implementation repository should contain:

```text
README.md
AGENTS.md
ARCHITECTURE.md
SECURITY.md
THREAT_MODEL.md
DATA_MODEL.md
API.md
SOURCE_ADAPTERS.md
EVENT_MODEL.md
WATCH_MODEL.md
AI.md
OBSERVABILITY.md
DEPLOYMENT.md
COST_MODEL.md
ADR/
```

The implementing AI must update documentation when architecture changes.

---

# 55. Required Architecture Decision Records

At minimum:

```text
ADR-001 Multi-Tenant Architecture
ADR-002 Modular Monolith
ADR-003 PostgreSQL as Source of Truth
ADR-004 Event-Centric Domain
ADR-005 Source Adapter Architecture
ADR-006 Evidence Immutability
ADR-007 AI Provider Abstraction
ADR-008 PostgreSQL Job Queue
ADR-009 Adaptive Polling
ADR-010 Tenant Isolation
ADR-011 Generic Web Fetch Security
ADR-012 Notification Idempotency
ADR-013 Search Strategy
ADR-014 Market Data Separation
```

---

# 56. Final Target Architecture

```text
                         ANDROID CLIENT
                              │
                              ▼
                       ┌──────────────┐
                       │ API / AUTH   │
                       └──────┬───────┘
                              │
                    ┌─────────▼─────────┐
                    │ APPLICATION CORE │
                    └─────────┬─────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
    SOURCES                WATCHES              SEARCH
        │                     │                     │
        ▼                     │                     │
    INGESTION                 │                     │
        │                     │                     │
        ▼                     │                     │
    EVIDENCE                  │                     │
        │                     │                     │
        ▼                     │                     │
     EVENTS ◄─────────────────┘                     │
        │                                           │
        ▼                                           │
    ENTITIES                                        │
        │                                           │
        └─────────────────────┬─────────────────────┘
                              │
                     ┌────────▼────────┐
                     │  INTELLIGENCE   │
                     │     ENGINE      │
                     └────────┬────────┘
                              │
             ┌────────────────┼────────────────┐
             ▼                ▼                ▼
         TIMELINES          ALERTS          AI GATEWAY
             │                │                │
             │                ▼                ├── Gemini
             │          ┌───────────┐          ├── OpenAI
             │          │    FCM    │          ├── Anthropic
             │          └─────┬─────┘          └── Local
             │                │
             ▼                ▼
          ANDROID         NOTIFICATION

DATA LAYER
────────────────────────────────────────────────────

PostgreSQL
├── Tenants
├── Sources
├── Evidence
├── Events
├── Entities
├── Watches
├── Alerts
├── Notifications
└── Jobs

Object Storage
└── Raw / large evidence where appropriate

pgvector
└── Semantic retrieval when justified

EXTERNAL SOURCES
────────────────────────────────────────────────────

GitHub
RSS
Atom
REST APIs
JSON APIs
Websites
SEC
Reddit
YouTube
Government sources
Market providers
Research sources
Custom endpoints
```

---

# 57. Product Evolution

The intended evolution is:

```text
Developer News
      ↓
Developer Intelligence
      ↓
Multi-domain Intelligence
      ↓
Personal Intelligence Monitoring
      ↓
Shared Intelligence Platform
      ↓
General-purpose Intelligence Infrastructure
```

The initial implementation must therefore avoid assumptions that make Developer Intelligence a permanent special case.

GitHub is the first domain because it provides structured, high-value, developer-specific events and a relatively clean source model.

The final product is not defined by GitHub, RSS, or news.

It is defined by the abstraction:

> **Monitor exact sources for exact things, understand what changed, preserve the evidence and history, and tell me when it matters.**

---

# 58. Immediate Implementation Order

The implementing AI should execute in exactly this order unless an ADR documents a justified deviation:

```text
PHASE 0
Specification + repository foundation

↓
PHASE 1
Multi-tenant backend

↓
PHASE 2
Source abstraction + resolver

↓
PHASE 3
Evidence system

↓
PHASE 4
GitHub end-to-end vertical slice

↓
PHASE 5
Event engine

↓
PHASE 6
Entity system

↓
PHASE 7
Watch engine

↓
PHASE 8
Scheduler + adaptive monitoring

↓
PHASE 9
Android client

↓
PHASE 10
AI intelligence layer

↓
PHASE 11
Search + investigations

↓
PHASE 12
Historical intelligence

↓
PHASE 13
Generic web intelligence

↓
PHASE 14
Security + production hardening

↓
PHASE 15
Performance + cost optimization

↓
PHASE 16
Market intelligence

↓
PHASE 17
Additional domains

↓
PHASE 18
Shared watches

↓
PHASE 19
Intelligence graph + correlation
```

Do not skip directly to later-domain features before the core Source → Evidence → Event → Entity → Watch → Alert pipeline is reliable.

---

# 59. Final Engineering Objective

The system should make the following interaction possible with minimal friction:

```text
USER

"Monitor new releases from androidx/androidx.
Tell me if anything related to Compose changes."

                    ↓

SOURCE RESOLUTION

GitHub
└── androidx/androidx
    └── releases

                    ↓

WATCH

Entity:
Jetpack Compose

Event:
Software Release

Filter:
Compose-related

Notification:
Immediate

                    ↓

CONTINUOUS MONITORING

GitHub
    ↓
New release
    ↓
Evidence
    ↓
Event
    ↓
Entity resolution
    ↓
Relevance
    ↓
Significance
    ↓
Watch match
    ↓
Alert
    ↓
FCM

                    ↓

USER

"Compose 1.x Released"

                    ↓

EVENT DETAIL

What happened
Why it matters
Confidence
Sources
Evidence
Related entities
Historical timeline
```

That is the first complete product loop.

Everything else should be built around making this loop **accurate, fast, secure, explainable, inexpensive, and extensible**.
