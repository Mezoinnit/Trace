package com.mezo.trace.server.api.itest

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimitApiTest {

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"

    @Test
    fun `auth endpoints enforce the per-ip limit with retry-after`() = testApplication {
        app(authLimit = 2)
        val client = createClient { expectSuccess = false }

        // Two attempts consume the window (they fail auth, but still count).
        repeat(2) {
            client.postJson("/api/v1/auth/signin", """{"email":"${uniqueEmail()}","password":"password-123"}""")
        }
        val third = client.postJson("/api/v1/auth/signin", """{"email":"${uniqueEmail()}","password":"password-123"}""")

        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertTrue(third.headers.contains("Retry-After"))
        val body = Json.parseToJsonElement(third.bodyAsText()).jsonObject["error"] as kotlinx.serialization.json.JsonObject
        assertEquals("\"RATE_LIMITED\"", body["code"].toString())
    }
}
