package com.mezo.trace.server.api.itest

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tenant isolation matrix (ADR-010): a valid authenticated user must not be
 * able to read or mutate another workspace's resources. Cross-tenant access
 * is reported as 404 to avoid resource enumeration.
 */
class TenantIsolationApiTest {


    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"

    @Test
    fun `user B cannot access user A's workspace in any way`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        // Two independent tenants.
        val a = register(client, uniqueEmail())
        val b = register(client, uniqueEmail())
        val workspaceA = a.workspaceId()

        fun bearerOf(body: kotlinx.serialization.json.JsonObject) =
            body["access_token"].toString().trim('"')

        val tokenA = bearerOf(a)
        val tokenB = bearerOf(b)

        // Sanity: owner A can read their own workspace.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/workspaces/$workspaceA") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }.status)

        // B gets 404 (not 403) for every operation on A's workspace.
        listOf(
            client.get("/api/v1/workspaces/$workspaceA") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
            client.get("/api/v1/workspaces/$workspaceA/members") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
            run {
                val r = client.patch("/api/v1/workspaces/$workspaceA") {
                    header(HttpHeaders.Authorization, "Bearer $tokenB")
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody("""{"name":"hijacked"}""")
                }
                r
            },
            client.delete("/api/v1/workspaces/$workspaceA") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            },
        ).forEach { response ->
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        // The rename did not happen.
        val after = client.get("/api/v1/workspaces/$workspaceA") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertFalse(after.bodyAsText().contains("hijacked"))

        // B's own listing does not include A's workspace.
        val bWorkspaces = client.get("/api/v1/workspaces") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertFalse(bWorkspaces.bodyAsText().contains(workspaceA))

        // Garbage and foreign-format ids are 404 as well.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/workspaces/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            }.status,
        )
    }

    @Test
    fun `owner can rename but members-only endpoints respect role`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val registered = register(client, uniqueEmail())
        val token = registered["access_token"].toString().trim('"')
        val workspaceId = registered.workspaceId()

        // Rename as OWNER succeeds.
        val renamed = client.patch("/api/v1/workspaces/$workspaceId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody("""{"name":"Renamed Workspace"}""")
        }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertTrue(renamed.bodyAsText().contains("Renamed Workspace"))

        // Members list shows the single owner.
        val members = client.get("/api/v1/workspaces/$workspaceId/members") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, members.status)
        assertTrue(members.bodyAsText().contains("\"OWNER\""))

        // Delete works for the owner.
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/workspaces/$workspaceId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status,
        )

        // And it is gone.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/workspaces/$workspaceId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status,
        )
    }
}
