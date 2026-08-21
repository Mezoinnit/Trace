# AGENTS.md — Engineering Rules for the Implementing AI

This file is binding on any AI agent (or human) modifying this repository.
It derives from the engineering rules in the master plan (section 53).

## Repository

- Backend lives in `backend/` (own Gradle build, Kotlin + Ktor).
- Android lives in `android/` (own Gradle build, Kotlin + Jetpack Compose).
- Architecture documentation lives at the repository root and under `docs/adr/`.
- **Update documentation when architecture changes.** If a doc references a
  contract you changed, update the doc in the same change.

## Build and verification commands

Run from the relevant directory.

- Backend:
  - Build + test: `./gradlew build`
  - Test: `./gradlew test`
  - Run API: `./gradlew :server:api:run`
  - Postgres: `docker compose up -d`
- Android:
  - Build: `./gradlew :app:assembleDebug`
  - Tests: `./gradlew :app:testDebugUnitTest`

Always run the build for the code you changed before finishing.

## Rule 1 — Do not invent requirements

If something is not specified, prefer, in order:

1. existing architecture;
2. established domain constraints (the master plan);
3. the simplest safe behavior;
4. a documented ADR.

Do not silently introduce major product behavior.

## Rule 2 — Preserve domain boundaries

- No business logic in controllers, Compose screens, database models, or HTTP
  adapters.
- Use application/domain layers. Backend dependency direction is:
  `server → infrastructure → application → domain`. `domain` has no dependencies.

## Rule 3 — External systems are untrusted

URLs, HTTP responses, RSS/Atom feeds, GitHub content, AI output, and user input
are all untrusted. Validate everything. Follow `SECURITY.md`.

## Rule 4 — Idempotency is mandatory

Any operation that can be retried must be safe to retry. Use stable idempotency
keys (e.g. `source_id + external_id` for evidence, `watch_id + event_id` for
alerts, `alert_id + device_id` for notification dispatch).

## Rule 5 — No silent data loss

Never:

- overwrite historical evidence without versioning;
- silently merge conflicting events (mark `CONTESTED` instead);
- silently delete source data;
- drop failed jobs without recording the failure.

## Rule 6 — No unbounded operations

Every expensive operation needs a bound: feed entries per cycle, fetch size,
response body size, redirect count, AI tokens, concurrent fetches, pagination.

## Rule 7 — No hidden background behavior

Users must understand what they are watching, which sources are monitored, why
an alert was generated, and when a source is unhealthy. Surface source health.

## Rule 8 — Prefer structured data

If a provider exposes an API, feed, webhook, or structured endpoint, prefer it
over scraping. Generic HTML extraction is a degraded adapter (see
`SOURCE_ADAPTERS.md`).

## Rule 9 — AI must be replaceable

Never couple the core system to a single AI provider. All AI access goes through
the `AiGateway` abstraction (`AI.md`). The system must run without AI.

## Rule 10 — Build for $0 development

Do not introduce paid dependencies unless there is no practical free/open-source
alternative and the decision is documented in an ADR. No paid API may be a hard
dependency for V1 development.

## Migrations

- All schema changes are versioned SQL migrations under
  `backend/infrastructure/persistence/src/main/resources/db/migration`.
- Never use destructive migrations. Never use
  `fallbackToDestructiveMigration` (or equivalents).
- Migrations must be safe to run forward only.

## Secrets

- Never commit secrets, tokens, API keys, or passwords.
- Local secrets go in `backend/.env` (gitignored); `backend/.env.example` holds
  placeholders.
- Never put provider credentials in the Android app.

## Definition of done (every change)

- Implementation exists.
- Automated tests exist and pass.
- Failure cases are covered.
- Security implications are addressed.
- Tenant isolation is preserved (never trust a client-supplied `workspace_id`).
- Documentation is updated if contracts changed.
- `./gradlew build` (backend and/or android, as applicable) succeeds.