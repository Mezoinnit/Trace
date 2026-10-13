package com.mezo.trace.application.port

/**
 * Operational metrics (Phase 14, OBSERVABILITY.md). Minimal, vendor-neutral
 * counter/gauge surface so the core never couples to a monitoring library
 * (mirrors the AiGateway replaceability rule).
 *
 * Counters are cumulative; gauge suppliers are re-read on scrape.
 */
interface MetricsPort {
    fun increment(counter: String, tags: Map<String, String> = emptyMap())

    /**
     * Registers a gauge bound to a monotonic supplier. Re-registering the
     * same [gauge] replaces the supplier (idempotent wiring).
     */
    fun gauge(gauge: String, supplier: () -> Double)
}

/**
 * No-op implementation — the system runs fully without a metrics backend.
 */
class NoopMetrics : MetricsPort {
    override fun increment(counter: String, tags: Map<String, String>) = Unit
    override fun gauge(gauge: String, supplier: () -> Double) = Unit
}