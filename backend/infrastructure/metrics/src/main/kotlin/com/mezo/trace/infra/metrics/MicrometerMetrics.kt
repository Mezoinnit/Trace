package com.mezo.trace.infra.metrics

import com.mezo.trace.application.port.MetricsPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.function.ToDoubleFunction

/**
 * Micrometer-backed [MetricsPort] (Phase 14, OBSERVABILITY.md, Rule 10 —
 * micrometer-core is free/OSS with no server dependency).
 */
class MicrometerMetrics(registry: MeterRegistry = SimpleMeterRegistry()) : MetricsPort {

    val registry: MeterRegistry = registry

    override fun increment(counter: String, tags: Map<String, String>) {
        Counter.builder(counter).tags(tags.map { (k, v) -> Tag.of(k, v) }).register(registry).increment()
    }

    override fun gauge(gauge: String, supplier: () -> Double) {
        Gauge.builder(gauge, supplier, ToDoubleFunction { s -> s() })
            .strongReference(true)
            .register(registry)
    }
}