package com.mezo.trace.server.api.itest

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 0–10 audit remediation:
 * - R1: event detail exposes supporting evidence + entities + attribution (§3.5)
 * - R3: watch notification_mode stored, validated, echoed (§4.14)
 */
class AuditRemediation2ITest {

    private fun releaseJson(id: Long, tag: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$tag",
         "html_url": "https://github.com/acme/fix/releases/tag/$tag",
         "published_at": "2026-08-10T10:00:00Z", "prerelease": false,
         "draft": false, "body": "Release notes for $tag"}
        """.trimIndent()

    @Test
    fun `event detail exposes evidence with source attribution and entities`() = testApplication {
        val repo = "acme/fix-${UUID.randomUUID().toString().take(8)}"
        val fetcher = com.mezo.trace.adapters.testing.StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            com.mezo.trace.adapters.testing.StubHttpFetcher.Response(
                body = "[${releaseJson(9800L, "v1.0.0")}]" .toByteArray(),
            ),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "fix-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }
        val endpointId = container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", repo)!!.id,
            ).single().id
        }
        container.fetchEndpointNow.execute(endpointId)

        val eventId = container.txRunner.run { container.eventRepo.latestDiscovered(1).single().id }
        val detail = client.get("/api/v1/events/$eventId") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }

        assertEquals(io.ktor.http.HttpStatusCode.OK, detail.status)
        val body = detail.bodyAsText()
        // Evidence with canonical URL + source attribution (plan §3.5, §12).
        assertTrue(body.contains("\"evidence\""))
        assertTrue(body.contains("canonical_url"))
        assertTrue(body.contains("\"OFFICIAL\""), "source trust must be attributed")
        assertTrue(body.contains("releases/tag/v1.0.0"))
        // Linked repository entity.
        assertTrue(body.contains("\"entities\""))
        assertTrue(body.contains(repo))
    }

    @Test
    fun `notification mode persists and validates`() = testApplication {
        val container = app()
        val client = createClient { expectSuccess = false }
        val session = register(client, "nm-${UUID.randomUUID()}@example.com")
        val token = session.accessToken()

        val suffix = UUID.randomUUID().toString().take(8)
        container.txRunner.run {
            container.createSource.execute(
                com.mezo.trace.domain.UserId(userIdOf(session)),
                "github://acme/nm-$suffix/releases",
                "github",
                null,
            )
        }
        val endpointId = endpointIdOf(container, "acme/nm-$suffix")

        // Valid mode persists and echoes back.
        val created = client.post("/api/v1/watches") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"workspace_id":"${session.workspaceId()}","name":"never-ping",
                    "endpoint_ids":["$endpointId"],
                    "notification_mode":"NEVER"}""",
            )
        }
        assertEquals(io.ktor.http.HttpStatusCode.Created, created.status)
        assertTrue(created.bodyAsText().contains("\"notification_mode\":\"NEVER\""))

        // Invalid mode is rejected server-side.
        val invalid = client.post("/api/v1/watches") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"workspace_id":"${session.workspaceId()}","name":"bad",
                    "endpoint_ids":["$endpointId"],
                    "notification_mode":"SMS"}""",
            )
        }
        assertEquals(io.ktor.http.HttpStatusCode.BadRequest, invalid.status)

        // PATCH can switch modes with optimistic locking.
        val watchId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").find(created.bodyAsText())!!.groupValues[1]
        val patched = client.patch("/api/v1/watches/$watchId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"expected_version":1,"notification_mode":"IMMEDIATE"}""")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, patched.status)
        assertTrue(patched.bodyAsText().contains("\"notification_mode\":\"IMMEDIATE\""))
    }

    private fun userIdOf(session: kotlinx.serialization.json.JsonObject): String =
        (session["user"] as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"')

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer, identity: String): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", identity)!!.id,
            ).single().id
        }
}
