package com.mezo.trace.server.api

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * Renders a Micrometer registry in the Prometheus text exposition format
 * (Phase 14, OBSERVABILITY.md). Kept explicit and dependency-light on
 * purpose: counters and gauges only, fixed tag values (no cardinality risk).
 */
object MetricsPrometheus {

    fun scrape(registry: MeterRegistry): String = buildString {
        registry.meters.forEach { meter ->
            val name = meter.id.name
            val labels = meter.id.tags.joinToString(",") { "${it.key}=\"${it.value}\"" }
            val labelSet = if (labels.isEmpty()) "" else "{$labels}"
            when (val m = meter) {
                is Counter -> {
                    appendLine("# TYPE $name counter")
                    appendLine("$name$labelSet ${m.count()}")
                }

                is Gauge ->
                    m.value().takeIf { it.isFinite() }?.let {
                        appendLine("# TYPE $name gauge")
                        appendLine("$name$labelSet $it")
                    }

                is Timer -> {
                    appendLine("# TYPE ${name}_seconds gauge")
                    appendLine("${name}_seconds$labelSet ${m.totalTime(TimeUnit.SECONDS)}")
                    appendLine("# TYPE ${name}_count counter")
                    appendLine("${name}_count$labelSet ${m.count()}")
                }

                else -> Unit
            }
        }
    }
}
