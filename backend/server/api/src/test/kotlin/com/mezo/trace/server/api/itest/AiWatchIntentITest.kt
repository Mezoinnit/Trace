package com.mezo.trace.server.api.itest

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 10 exit criterion: "Monitor AndroidX releases and notify me about
 * Compose changes" yields a correct structured Watch proposal BEFORE any
 * confirmation, and the semantic watch field participates in matching.
 */
class AiWatchIntentITest {

    @Test
    fun `interpret returns a confirmable proposal and records the operation`() = testApplication {
        val container = app()
        val client = createClient { expectSuccess = false }
        val session = register(client, "ai-${UUID.randomUUID()}@example.com")

        val response = client.post("/api/v1/watches/interpret") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                """{"input":"Monitor new releases in androidx/androidx and notify
                       me when Compose changes"}""",
            )
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObjectSafe()
        assertEquals("\"github://androidx/androidx/releases\"", body["endpoint_uri"].toString())
        assertEquals("\"github\"", body["adapter_id"].toString())
        assertTrue(body["keywords_include"].toString().contains("compose"))
        assertEquals("[]", body["uncertainties"].toString())

        // Reproducibility ledger (plan Phase 10 rules).
        assertTrue(aiOperationCount("PARSE_WATCH_INTENT") >= 1)
    }

    @Test
    fun `oversized input is rejected before any provider runs`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val session = register(client, "ai-${UUID.randomUUID()}@example.com")

        val long = "x".repeat(501)
        val response = client.post("/api/v1/watches/interpret") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"input":"$long"}""")
        }
        assertEquals(io.ktor.http.HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

    @Test
    fun `semantic-constrained watch filters events via relevance`() = testApplication {
        val repo = "acme/sem-${UUID.randomUUID().toString().take(8)}"
        val fixture = "[" + listOf(
            releaseJson(9700L, "v1.0.0", "Compose compiler update", "compiler notes"),
            releaseJson(9701L, "v1.1.0", "Gradle plugin refresh", "gradle only"),
        ).joinToString(",") + "]"
        val fetcher = com.mezo.trace.adapters.testing.StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            com.mezo.trace.adapters.testing.StubHttpFetcher.Response(body = fixture.toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "sem-${UUID.randomUUID()}@example.com")
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

        // Watch with ONLY a semantic constraint (no keywords) — activates the
        // stored-but-previously-unmatched field through the AI gateway.
        container.txRunner.run {
            container.createWatch.execute(
                com.mezo.trace.domain.UserId(userIdOf(session)),
                session.workspaceId(),
                "compose-only",
                endpointIds = setOf(endpointId),
                eventTypes = emptySet(),
                rules = com.mezo.trace.application.usecase.watches.CreateWatch.Rules(
                    semantic = "compose compiler changes",
                ),
            )
        }

        container.fetchEndpointNow.execute(endpointId)

        val titles = container.txRunner.run {
            container.alertRepo.listForWorkspace(session.workspaceId(), 100)
                .mapNotNull { container.eventRepo.findById(it.eventId)?.title }
        }
        assertEquals(listOf("Compose compiler update"), titles)
    }

    // ---- helpers ----

    private fun releaseJson(id: Long, tag: String, name: String, body: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$name",
         "html_url": "https://github.com/x/y/tag/$tag",
         "published_at": "2026-08-10T10:00:00Z", "prerelease": false,
         "draft": false, "body": "$body"}
        """.trimIndent()

    private fun userIdOf(session: kotlinx.serialization.json.JsonObject): String =
        (session["user"] as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"')

    private fun aiOperationCount(action: String): Int =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM ai_operations WHERE operation = ?").use { ps ->
                ps.setString(1, action)
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe() =
        this as kotlinx.serialization.json.JsonObject
}
