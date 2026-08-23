package com.mezo.trace.server.api.itest

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 source-system API: resolve → create → inspect → enable/disable.
 * GitHub resolution is host-pattern based, so no network is required.
 */
class SourcesApiITest {

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"

    private suspend fun registerAndGetToken(client: io.ktor.client.HttpClient): String {
        val body = register(client, uniqueEmail())
        return body.accessToken()
    }

    @Test
    fun `resolve proposes exact endpoints for github urls`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val token = registerAndGetToken(client)

        val response = client.post("/api/v1/sources/resolve") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"input":"https://github.com/androidx/androidx/releases"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val proposal = Json.parseToJsonElement(response.bodyAsText()).jsonObjectSafe()
        assertEquals("\"GITHUB\"", proposal["provider"].toString())
        assertEquals("\"github://androidx/androidx/releases\"", proposal["endpoint_uri"].toString())
        assertEquals("\"RELEASES\"", proposal["endpoint_type"].toString())
        assertEquals("\"OFFICIAL\"", proposal["trust"].toString())
    }

    @Test
    fun `create registers source and endpoint idempotently`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val token = registerAndGetToken(client)
        val createBody = """
            {"endpoint_uri":"github://androidx/androidx/releases","adapter_id":"github"}
        """.trimIndent()

        val first = client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(createBody)
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val detail = Json.parseToJsonElement(first.bodyAsText()).jsonObjectSafe()
        assertEquals("\"androidx/androidx\"", detail["canonical_identity"].toString())
        assertEquals(1, (detail["endpoints"] as kotlinx.serialization.json.JsonArray).size)

        // Re-registering the same endpoint converges on the same rows.
        val second = client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(createBody)
        }
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.bodyAsText().contains(first.bodyAsText().substringBefore("\"endpoints\"")))
    }

    @Test
    fun `unknown adapters and unsupported endpoints are rejected`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val token = registerAndGetToken(client)

        // web_page adapter cannot handle github:// URIs.
        val response = client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://o/r/releases","adapter_id":"web_page"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))

        // Unknown adapter ids are rejected.
        val unknownAdapter = client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"https://example.com/feed.xml","adapter_id":"does_not_exist"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknownAdapter.status)
    }

    @Test
    fun `list get and disable sources with health reporting`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val token = registerAndGetToken(client)

        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://androidx/androidx/releases","adapter_id":"github"}""")
        }

        val list = client.get("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("androidx/androidx"))

        val sourceId = extractSourceId(list.bodyAsText())
        val detail = client.get("/api/v1/sources/$sourceId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, detail.status)
        // Health initialized at creation (plan §9).
        assertTrue(detail.bodyAsText().contains("\"HEALTHY\""))

        val disabled = client.patch("/api/v1/sources/$sourceId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DISABLED"}""")
        }
        assertEquals(HttpStatusCode.OK, disabled.status)
        assertTrue(disabled.bodyAsText().contains("\"DISABLED\""))
    }

    private fun extractSourceId(listBody: String): String =
        Regex("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").find(listBody)!!.groupValues[1]

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe() =
        this as kotlinx.serialization.json.JsonObject
}
