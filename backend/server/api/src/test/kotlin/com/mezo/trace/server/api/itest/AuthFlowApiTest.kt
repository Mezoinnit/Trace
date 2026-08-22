package com.mezo.trace.server.api.itest

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthFlowApiTest {

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"

    @Test
    fun `register, me, refresh and signout complete the session lifecycle`() = testApplication {
        app()
        val client = createClient {
            expectSuccess = false
        }
        val email = uniqueEmail()

        // Register → 201 with tokens and personal workspace.
        val registered = register(client, email)
        assertTrue(registered.containsKey("refresh_token"))
        assertTrue((registered["workspace"] as JsonObject).containsKey("name"))

        // Access /me with the bearer token.
        val meResponse = client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer ${registered.accessToken()}")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
        val me = Json.parseToJsonElement(meResponse.bodyAsText()).jsonObject
        assertEquals("\"$email\"", me["user"].toString().let { u ->
            (me["user"] as JsonObject)["email"].toString()
        })

        // Refresh issues a new token pair (the random refresh token must differ;
        // access tokens are deterministic within the same second).
        val refreshed = Json.parseToJsonElement(
            client.postJson("/api/v1/auth/refresh", """{"refresh_token":"${registered.refreshToken()}"}""")
                .bodyAsText(),
        ).jsonObject
        assertTrue(refreshed.refreshToken() != registered.refreshToken())

        // The rotated pair is fully usable.
        val meAfter = client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer ${refreshed.accessToken()}")
        }
        assertEquals(HttpStatusCode.OK, meAfter.status)

        // Signout revokes the family; replaying either refresh token fails.
        assertEquals(HttpStatusCode.NoContent, client.authedPost("/api/v1/auth/signout", refreshed.accessToken(), """{"refresh_token":"${refreshed.refreshToken()}"}""").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/v1/auth/refresh", """{"refresh_token":"${refreshed.refreshToken()}"}""").status,
        )
    }

    @Test
    fun `register returns validation error for bad email`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val response = client.postJson("/api/v1/auth/register", """{"email":"nope","password":"password-123"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"] as JsonObject
        assertEquals("\"VALIDATION_ERROR\"", body["code"].toString())
        assertNotNull(body["request_id"])
    }

    @Test
    fun `register rejects weak passwords`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val response = client.postJson(
            "/api/v1/auth/register",
            """{"email":"${uniqueEmail()}","password":"short"}""",
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `duplicate email registration conflicts`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val email = uniqueEmail()
        register(client, email)

        val response = client.postJson("/api/v1/auth/register", """{"email":"$email","password":"password-123"}""")

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `signin requires correct password`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val email = uniqueEmail()
        register(client, email)

        val wrong = client.postJson("/api/v1/auth/signin", """{"email":"$email","password":"wrong-pass-99"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val right = signin(client, email)
        assertTrue(right.containsKey("access_token"))
    }

    @Test
    fun `me without a bearer token is unauthenticated`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val response = client.get("/api/v1/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
