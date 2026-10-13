package com.mezo.trace.server.api.itest

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase 14 mandatory security matrix: auth abuse. Signing in with an unknown
 * account and with a wrong password must be indistinguishable (no account
 * enumeration), and rotating spoofed forwarding headers must not bypass the
 * per-IP rate limit.
 */
class AuthAbuseITest {

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"

    private fun errorBodyOf(body: String): JsonObject =
        (Json.parseToJsonElement(body).jsonObject)["error"] as JsonObject

    @Test
    fun `unknown account and wrong password are indistinguishable`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val knownEmail = uniqueEmail()
        register(client, knownEmail)

        val unknownEmail = "ghost-${UUID.randomUUID()}@example.com"

        val unknownAccount = client.postJson(
            "/api/v1/auth/signin",
            """{"email":"$unknownEmail","password":"whatever-123"}""",
        )
        val wrongPassword = client.postJson(
            "/api/v1/auth/signin",
            """{"email":"$knownEmail","password":"definitely-wrong"}""",
        )

        assertEquals(HttpStatusCode.Unauthorized, unknownAccount.status)
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)

        val unknownBody = errorBodyOf(unknownAccount.bodyAsText())
        val wrongBody = errorBodyOf(wrongPassword.bodyAsText())
        assertEquals(unknownBody["code"], wrongBody["code"], "same error code")
        assertEquals(unknownBody["message"], wrongBody["message"], "same message — no enumeration oracle")
    }

    @Test
    fun `spoofed forwarding headers do not bypass the per-ip auth limit`() = testApplication {
        app(authLimit = 2)
        val client = createClient { expectSuccess = false }

        suspend fun attempt(forwardedFor: String) = client.post("/api/v1/auth/signin") {
            header("X-Forwarded-For", forwardedFor)
            header(HttpHeaders.XForwardedFor, forwardedFor)
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${uniqueEmail()}","password":"password-123"}""")
        }

        // Two attempts fill the window...
        assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.7").status)
        assertEquals(HttpStatusCode.Unauthorized, attempt("198.51.100.9").status)
        // ...the third is limited even though it claims a different client IP.
        val third = attempt("192.0.2.77")
        assertEquals(HttpStatusCode.TooManyRequests, third.status, "header rotation must not bypass the limit")
    }
}
