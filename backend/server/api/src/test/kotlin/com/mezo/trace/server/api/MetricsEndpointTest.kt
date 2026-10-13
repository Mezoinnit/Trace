package com.mezo.trace.server.api

import com.mezo.trace.infra.persistence.DatabaseConfig
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.testsupport.testSecurityConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test


class MetricsEndpointTest {

    private fun testConfig() =
        DatabaseConfig(jdbcUrl = "jdbc:postgresql://localhost:5432/unreachable", maximumPoolSize = 1)

    @Test
    fun `metrics endpoint exposes prometheus counters`() = testApplication {
        val dataSource = DatabaseFactory.createDataSource(testConfig())
        application { module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000)) }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("fetch_total"), "must expose fetch_total")
        assertTrue(body.contains("evidence_created_total"), "must expose evidence_created_total")
        assertTrue(body.contains("alerts_created_total"), "must expose alerts_created_total")
    }

    @Test
    fun `prometheus renderer emits counters and finite gauges only`() {
        val metrics = com.mezo.trace.infra.metrics.MicrometerMetrics()
        metrics.increment("fetch_total", mapOf("outcome" to "success"))
        metrics.increment("fetch_total", mapOf("outcome" to "success"))
        metrics.increment("fetch_total", mapOf("outcome" to "network_failed"))

        val text = MetricsPrometheus.scrape(metrics.registry)

        assertTrue(text.contains("# TYPE fetch_total counter"))
        assertTrue(text.contains("""fetch_total{outcome="success"} 2.0"""))
        assertTrue(text.contains("""fetch_total{outcome="network_failed"} 1.0"""))
    }

    @Test
    fun `metrics port increments are observable through the container`() {
        val metrics = com.mezo.trace.infra.metrics.MicrometerMetrics()
        val port: com.mezo.trace.application.port.MetricsPort = metrics

        port.increment("alerts_created_total")
        port.increment("alerts_created_total")

        val text = MetricsPrometheus.scrape(metrics.registry)
        assertTrue(text.contains("alerts_created_total 2.0"))
    }
}
