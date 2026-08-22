package com.mezo.trace.server.api

import com.mezo.trace.infra.persistence.DatabaseConfig
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.testsupport.testSecurityConfig
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthRouteTest {

    @Test
    fun `health endpoint responds ok`() = testApplication {
        // Lazy datasource: Hikari does not connect eagerly, so /health works
        // without a database (liveness is independent of readiness).
        val dataSource = DatabaseFactory.createDataSource(
            DatabaseConfig(jdbcUrl = "jdbc:postgresql://localhost:5432/unreachable", maximumPoolSize = 1),
        )
        application { module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000)) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
