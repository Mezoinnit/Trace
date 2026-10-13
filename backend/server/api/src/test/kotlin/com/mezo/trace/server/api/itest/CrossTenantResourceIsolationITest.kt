package com.mezo.trace.server.api.itest

import io.ktor.client.request.delete
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Phase 14 mandatory security tests: cross-tenant access over every
 * tenant-owned resource type (ADR-010, ADR-017). A member of one workspace
 * gets 404 — never 403, never another tenant's data — for any read or
 * mutation of another workspace's resources.
 */
class CrossTenantResourceIsolationITest {

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"
    private val repoIdentity = "acme/isolation-${UUID.randomUUID().toString().take(8)}"

    @Test
    fun `watches alerts and workspaces of tenant A are invisible to tenant B`() = testApplication {
        // Tenant A: register, create a watch against a real GitHub endpoint.
        val container = app()
        val client = createClient { expectSuccess = false }

        val a = register(client, uniqueEmail())
        val tokenA = a.accessToken()
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repoIdentity/releases","adapter_id":"github"}""")
        }
        val endpointId = endpointIdOf(container, repoIdentity)
        val watchAResponse = client.authedPost(
            "/api/v1/watches",
            tokenA,
            """{"workspace_id":"${a.workspaceId()}","name":"A watch",
                "endpoint_ids":["$endpointId"]}""",
        )
        assertEquals(HttpStatusCode.Created, watchAResponse.status, watchAResponse.bodyAsText())
        val watchAId = Json.parseToJsonElement(watchAResponse.bodyAsText())
            .toString().substringAfter("\"id\":\"").substringBefore("\"")
        val workspaceA = a.workspaceId()

        // Tenant B: independent registration.
        val b = register(client, uniqueEmail())
        val tokenB = b.accessToken()
        val workspaceB = b.workspaceId()

        // B cannot read or mutate any of A's tenant-owned resources.
        listOf(
            "GET watch" to client.get("/api/v1/watches/$watchAId") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
            "PATCH watch" to client.patch("/api/v1/watches/$watchAId") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
                contentType(ContentType.Application.Json)
                setBody("""{"expected_version":1,"name":"hijacked"}""")
            },
            "DELETE watch" to client.delete("/api/v1/watches/$watchAId") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
            "GET A workspace" to client.get("/api/v1/workspaces/$workspaceA") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
            "PATCH A workspace" to client.patch("/api/v1/workspaces/$workspaceA") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"hijacked"}""")
            },
            "DELETE A workspace" to client.delete("/api/v1/workspaces/$workspaceA") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
            "GET A members" to client.get("/api/v1/workspaces/$workspaceA/members") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
        ).forEach { (label, response) ->
            assertEquals(HttpStatusCode.NotFound, response.status, "$label must be 404")
        }
        // Listings never leak the other tenant's identifiers.
        val bWatches = client.get("/api/v1/watches") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }.bodyAsText()
        assertFalse(bWatches.contains(watchAId), "B's watch list must not contain A's watch id")

        val bAlerts = client.get("/api/v1/alerts") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }.bodyAsText()
        assertFalse(bAlerts.contains(workspaceA), "B's alerts must not reference A's workspace")

        val bWorkspaces = client.get("/api/v1/workspaces") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }.bodyAsText()
        assertFalse(bWorkspaces.contains(workspaceA))
        assertTrue(bWorkspaces.contains(workspaceB), "sanity: B must still see the own workspace")

        // A's data is untouched.
        val intact = client.get("/api/v1/watches/$watchAId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, intact.status)
        assertFalse(intact.bodyAsText().contains("hijacked"))
    }

    @Test
    fun `garbage and foreign uuids are 404 not 500`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val b = register(client, uniqueEmail())
        val tokenB = b.accessToken()

        listOf(
            "/api/v1/watches/not-a-uuid",
            "/api/v1/workspaces/${UUID.randomUUID()}",
            "/api/v1/watches/${UUID.randomUUID()}",
        ).forEach { path ->
            val response = client.get(path) {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            }
            assertEquals(HttpStatusCode.NotFound, response.status, "GET $path must be 404")
        }
    }

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer, identity: String): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", identity)!!.id,
            ).single().id
        }
}
