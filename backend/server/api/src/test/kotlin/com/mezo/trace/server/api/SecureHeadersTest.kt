package com.mezo.trace.server.api

import com.mezo.trace.application.AppError
import com.mezo.trace.infra.persistence.DatabaseConfig
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.server.api.itest.postJson
import com.mezo.trace.testsupport.testSecurityConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureHeadersTest {

    /** Unreachable DB is fine: these tests assert headers/bounds, not data. */
    private fun testConfig() =
        DatabaseConfig(jdbcUrl = "jdbc:postgresql://localhost:5432/unreachable", maximumPoolSize = 1)

    @Test
    fun `security headers are present on every response`() = testApplication {
        val dataSource = DatabaseFactory.createDataSource(testConfig())
        application {
            module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000), SecureHeadersConfig())
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals(
            "default-src 'none'; frame-ancestors 'none'",
            response.headers["Content-Security-Policy"],
        )
    }

    @Test
    fun `hsts is absent by default and present when enabled`() {
        testApplication {
            val dataSource = DatabaseFactory.createDataSource(testConfig())
            application {
                module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000), SecureHeadersConfig())
            }
            assertNull(client.get("/health").headers["Strict-Transport-Security"])
        }
        testApplication {
            val dataSource = DatabaseFactory.createDataSource(testConfig())
            val config = SecureHeadersConfig().apply { hstsEnabled = true }
            application {
                module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000), config)
            }
            assertEquals(
                "max-age=31536000; includeSubDomains",
                client.get("/health").headers["Strict-Transport-Security"],
            )
        }
    }

    @Test
    fun `api responses are no-store`() = testApplication {
        val dataSource = DatabaseFactory.createDataSource(testConfig())
        application {
            module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000), SecureHeadersConfig())
        }

        val response = client.get("/api/v1/sources")

        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `body bound decision is scoped to api body methods only`() {
        // Outside /api/ — never bounded.
        assertNull(bodyBoundDecision("/health", HttpMethod.Post, null, 1024))
        // GET has no body — never bounded.
        assertNull(bodyBoundDecision("/api/v1/events", HttpMethod.Get, null, 1024))
        // Missing length on an API POST → 411.
        assertTrue(bodyBoundDecision("/api/v1/auth/signin", HttpMethod.Post, null, 1024) is AppError.LengthRequired)
        // Over the limit → 413.
        assertTrue(
            bodyBoundDecision("/api/v1/auth/signin", HttpMethod.Post, 1025L, 1024L) is AppError.PayloadTooLarge,
        )
        // Exactly at the limit → allowed.
        assertNull(bodyBoundDecision("/api/v1/auth/signin", HttpMethod.Post, 1024L, 1024L))
    }

    @Test
    fun `oversized json body is rejected with 413`() = testApplication {
        val dataSource = DatabaseFactory.createDataSource(testConfig())
        val config = SecureHeadersConfig().apply { maxBodyBytes = 1024 }
        application {
            module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000), config)
        }

        val oversized = "{\"email\":\"a@b.c\",\"password\":\"" + "x".repeat(2048) + "\"}"
        val response = client.postJson("/api/v1/auth/register", oversized)

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun `normal json body is not rejected by the bounds check`() = testApplication {
        val dataSource = DatabaseFactory.createDataSource(testConfig())
        application {
            module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000), SecureHeadersConfig())
        }

        val response = client.postJson(
            "/api/v1/auth/register",
            """{"email":"headers-ok@example.test","password":"password-123"}""",
        )

        // Without a database the route cannot complete — but the failure must
        // be the generic internal error, never a body-bounds rejection.
        assertFalse(response.status == HttpStatusCode.PayloadTooLarge)
        assertFalse(response.status == HttpStatusCode.LengthRequired)
    }
}

