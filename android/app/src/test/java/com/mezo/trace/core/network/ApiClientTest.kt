package com.mezo.trace.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {

    private fun clientWith(handler: (request: MockHttpRequest) -> MockResponse): ApiClient {
        var captured: MockHttpRequest? = null
        val engine = MockEngine { request ->
            captured = MockHttpRequest(request.method.value, request.url.encodedPath, request.headers[HttpHeaders.Authorization])
            val response = handler(captured!!)
            respond(
                content = ByteReadChannel(response.body),
                status = response.status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return ApiClient("http://test", { "token-123" }, engine)
    }

    private data class MockHttpRequest(val method: String, val path: String, val authHeader: String?)
    private data class MockResponse(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

    @Test
    fun `session response parses and tokens flow into bearer header`() = runTest {
        lateinit var seen: MockHttpRequest
        val client = clientWith { request ->
            seen = request
            MockResponse(
                """
                {"user":{"id":"u1","email":"a@b.c","display_name":null},
                 "workspace":{"id":"w1","name":"Personal Workspace"},
                 "access_token":"at","access_token_expires_at":"2026-08-24T00:00:00Z",
                 "refresh_token":"rt"}
                """.trimIndent(),
            )
        }

        val session = client.signIn("a@b.c", "password-123")

        assertEquals("u1", session.user.id)
        assertEquals("w1", session.workspace.id)
        assertEquals("POST", seen.method)
        assertEquals("/api/v1/auth/signin", seen.path)
    }

    @Test
    fun `authenticated requests carry the freshest bearer token`() = runTest {
        lateinit var seen: MockHttpRequest
        val client = clientWith { request ->
            seen = request
            MockResponse("""{"items":[],"next_cursor":null}""")
        }

        val feed = client.events(cursor = null)

        assertTrue(feed.items.isEmpty())
        assertEquals("Bearer token-123", seen.authHeader)
        assertEquals("/api/v1/events", seen.path)
    }

    @Test
    fun `unknown json keys are ignored per API versioning policy`() = runTest {
        val client = clientWith {
            MockResponse(
                """{"items":[{"id":"e1","type":"SOFTWARE_RELEASE","title":"T",
                    "summary":null,"occurred_at":null,"discovered_at":"d",
                    "confidence":"HIGH","significance":"MEDIUM","status":"CONFIRMED",
                    "some_future_field":{"nested":true}}],"next_cursor":"cur"}""",
            )
        }

        val feed = client.events(null)

        assertEquals(1, feed.items.size)
        assertEquals("e1", feed.items[0].id)
        assertEquals("cur", feed.nextCursor)
    }

    @Test
    fun `error envelope maps to ApiException with server code`() = runTest {
        val client = clientWith {
            MockResponse(
                """{"error":{"code":"VALIDATION_ERROR","message":"email is not valid",
                    "request_id":"r1","details":[]}}""",
                HttpStatusCode.BadRequest,
            )
        }

        var thrown: ApiException? = null
        try {
            client.register("bad", "short", null)
        } catch (e: ApiException) {
            thrown = e
        }
        assertEquals("VALIDATION_ERROR", thrown?.code)
        assertEquals("email is not valid", thrown?.message)
    }
}
