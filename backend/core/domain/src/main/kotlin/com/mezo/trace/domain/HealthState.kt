package com.mezo.trace.domain

/**
 * Per-endpoint health state machine (plan §9, SOURCE_ADAPTERS.md).
 * Failed sources are visible to users and operators — never silently stopped.
 */
enum class HealthState {
    HEALTHY,
    DEGRADED,
    STALE,
    BLOCKED,
    AUTH_REQUIRED,
    RATE_LIMITED,
    PARSER_FAILED,
    NETWORK_FAILED,
    DISABLED,
}
