# ADR-015: Self-Hosted JWT Authentication

**Status:** Accepted  
**Date:** 2026-08-22

## Context

Phase 1 requires authentication. The plan prefers a proven provider but also
mandates $0 development with no paid or external cloud dependency (Rule 10).
Managed providers require accounts/network; Keycloak is a heavy operational
dependency for local dev and CI.

## Decision

Self-hosted session authentication built on well-tested cryptographic
primitives:

- **Passwords:** argon2id (BouncyCastle, OWASP params m=19456/t=2/p=1);
  parameters travel with the encoded hash.
- **Access tokens:** HS256 JWT (`com.auth0:java-jwt`), subject = user id,
  ~15 min TTL. Expiry enforced against the server clock at validation time.
- **Refresh tokens:** opaque 256-bit random values; only SHA-256 hashes are
  stored. Rotation on every use with **family-based reuse detection**:
  replaying a rotated/revoked token revokes the entire family and records a
  `REFRESH_TOKEN_REUSE` audit event.
- **Secrets:** `JWT_SECRET` from environment (≥32 bytes); production refuses
  to start without it. Dev derives a documented insecure default.
- All issuance/validation lives behind the application-layer `TokenIssuer`
  port (`infrastructure/security` adapter). Nothing else in the codebase knows
  about JWTs.

## Consequences

- $0, fully local, no external service in dev or CI.
- Swapping to an OIDC provider later means replacing one adapter plus login
  routes; domain/application logic is untouched.
- We own password-reset/email-verification eventually (needs mailer — future
  phase); signout-all and rotation semantics are already server-side.
- HS256 is acceptable while a single backend issues tokens; migrating to RS256/
  JWKS for multi-service verification is a config-level change behind the same
  port.

References: plan §26, SECURITY.md §7, API.md.
