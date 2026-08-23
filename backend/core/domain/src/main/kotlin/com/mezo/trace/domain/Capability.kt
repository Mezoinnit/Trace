package com.mezo.trace.domain

/**
 * Explicit endpoint capabilities. The scheduler and pipeline read these to
 * choose polling strategy and to gate what an endpoint may produce
 * (SOURCE_ADAPTERS.md).
 */
enum class Capability {
    RELEASES,
    FEED_ENTRIES,
    CONDITIONAL_POLL,
    HTML_EXTRACT,
    PAGINATED,
    RATE_LIMITED_API,
}
