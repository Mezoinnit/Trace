# ADR-011: Generic Web Fetch Security

**Status:** Accepted  
**Date:** 2026-08-21

## Context

Users can add arbitrary URLs as sources. Fetching untrusted URLs exposes the
platform to SSRF, DNS rebinding, redirect abuse, and resource exhaustion
(targeting internal services, cloud metadata endpoints, and private networks).

## Decision

- Fetch worker enforces, for every outbound request:
  - protocol allowlist (HTTPS by default; HTTP only when explicitly allowed);
  - block of private/link-local/loopback/metadata ranges by **resolved IP**
    (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
    `169.254.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`, plus cloud metadata);
  - DNS resolution + IP validation (DNS rebinding mitigation);
  - revalidation of destination after every redirect;
  - bounded redirects, connection/total timeouts, response and decompressed
    size caps;
  - bounded concurrency per tenant/source;
  - sanitization before parsing; never execute returned JavaScript; never
    render untrusted HTML in privileged contexts.
- Fetchers run in a restricted worker environment where practical.

## Consequences

- URL string checks are never sufficient; IP-level validation is mandatory.
- Generic web fetching is treated as a degraded, resource-bounded capability.
- This remains a verified control (SSRF/DNS rebinding tests, Phase 14). The
  fetcher's DNS resolver is injectable and re-resolves immediately before
  every connection (initial validation, redirect hops, and pre-`send`), which
  narrows the rebinding TOCTOU window to the transport's own connect and is
  proven by the rebinding test in `SsrfGuardTest`.

References: plan §11, §38.2, THREAT_MODEL.md T2.