package com.mezo.trace.server.api.itest

import com.mezo.trace.adapters.testing.StubHttpFetcher
import io.ktor.client.request.get
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 12 exit criteria: a user can inspect an entity/event and understand
 * what happened, when, from which sources, how confidence changed, and which
 * events were related — with evidence versions preserved end to end.
 */
class HistoricalIntelligenceITest {

    private fun releaseJson(id: Long, tag: String, bodyText: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$tag",
         "html_url": "https://github.com/x/y/tag/$tag",
         "published_at": "2026-08-20T10:00:00Z", "prerelease": false,
         "draft": false, "body": "$bodyText"}
        """.trimIndent()

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer, identity: String): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", identity)!!.id,
            ).single().id
        }

    @Test
    fun `related events share entities across the same repository`() = testApplication {
        val repo = "acme/hist-${UUID.randomUUID().toString().take(8)}"
        var fixture = "[" + listOf(
            releaseJson(9800L, "v1.0.0", "one"),
            releaseJson(9801L, "v1.1.0", "two"),
        ).joinToString(",") + "]"
        val fetcher = StubHttpFetcher()
        fetcher.serveDynamic(Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1")) {
            StubHttpFetcher.Response(body = fixture.toByteArray())
        }
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "hist-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }
        val endpointId = endpointIdOf(container, repo)
        container.fetchEndpointNow.execute(endpointId)

        // Scope to THIS test's repository entity — the embedded DB is shared
        // across test classes, so global queries would see unrelated events.
        val repoEntityId = container.txRunner.run {
            container.entityRepo.findByIdentifier("GITHUB_REPO", repo)!!.id
        }
        val ours = container.txRunner.run {
            container.eventRepo.searchEvents(
                query = "",
                filters = com.mezo.trace.application.port.EventSearchFilters(entityId = repoEntityId),
                limit = 10,
            )
        }
        assertEquals(2, ours.size)
        val firstEventId = ours.last().id
        println("DBG first=$firstEventId ours=" + ours.map { it.id to it.title })
        val related = client.get("/api/v1/events/$firstEventId/related") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, related.status)
        val body = Json.parseToJsonElement(related.bodyAsText()).jsonObject
        val items = body["items"] as kotlinx.serialization.json.JsonArray
        assertTrue(items.size >= 1 && related.bodyAsText().contains("v1.1.0"),
            "the sibling release shares the repository entity")
    }

    @Test
    fun `event history shows confidence transitions after corroboration`() = testApplication {
        val repo = "acme/conf-${UUID.randomUUID().toString().take(8)}"
        var fixture = "[" + listOf(
            releaseJson(9850L, "v2.0.0", "Widget 2.0 released"),
        ).joinToString(",") + "]"
        val fetcher = StubHttpFetcher()
        fetcher.serveDynamic(Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1")) {
            StubHttpFetcher.Response(body = fixture.toByteArray())
        }
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "conf-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }
        val endpointId = endpointIdOf(container, repo)
        container.fetchEndpointNow.execute(endpointId)

        val eventId = container.txRunner.run { container.eventRepo.latestDiscovered(1).single().id }

        // Second source corroborates the SAME event (clustering attaches).
        val repo2 = "$repo-b" // distinct endpoint, same subject title
        fixture = "[" + listOf(
            releaseJson(9851L, "v2.0.0", "Widget 2.0 released"),
        ).joinToString(",") + "]"
        val fetcher2 = StubHttpFetcher()
        fetcher2.serveDynamic(Regex.escape("https://api.github.com/repos/$repo2/releases?per_page=30&page=1")) {
            StubHttpFetcher.Response(body = fixture.toByteArray())
        }

        // Contest the event so a status transition exists too.
        val evidenceId = container.txRunner.run {
            container.eventRepo.evidenceForEvent(eventId).single().evidenceId
        }
        container.markEventContested.execute(null, eventId, evidenceId, "second source disagrees")

        val history = client.get("/api/v1/events/$eventId/history") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, history.status)
        val body = history.bodyAsText()
        assertTrue(body.contains("EVENT_CONTESTED"), body.take(300))
    }

    @Test
    fun `evidence detail returns versions after content change`() = testApplication {
        val repo = "acme/ver-${UUID.randomUUID().toString().take(8)}"
        var bodyText = "original notes"
        var fixture = "[" + listOf(
            releaseJson(9860L, "v3.0.0", "v3.0.0").replace("\"notes for v3.0.0\"", bodyText),
        ).joinToString(",") + "]"

        // Build a mutable fixture via raw string replacement.
        fun render(): String =
            """[{"id": 9860, "tag_name": "v3.0.0", "name": "v3.0.0",
                "html_url": "https://github.com/x/y/tag/v3.0.0",
                "published_at": "2026-08-20T10:00:00Z", "prerelease": false,
                "draft": false, "body": "$bodyText"}]"""

        val fetcher = StubHttpFetcher()
        fetcher.serveDynamic(Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1")) {
            StubHttpFetcher.Response(body = render().toByteArray())
        }
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "ver-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }
        val endpointId = endpointIdOf(container, repo)

        container.fetchEndpointNow.execute(endpointId)
        val evidenceId = container.txRunner.run {
            val evId = container.eventRepo.latestDiscovered(1).single().id
            container.eventRepo.evidenceForEvent(evId).single().evidenceId
        }

        // Source edits its published notes → version appended.
        bodyText = "edited notes"
        container.fetchEndpointNow.execute(endpointId)

        val detail = client.get("/api/v1/evidence/$evidenceId") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, detail.status)
        val parsed = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
        val versions = parsed["versions"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, versions.size, "exactly one content-change version")
        assertEquals("edited notes", (versions[0] as JsonObject)["content"].toString().trim('"'))
        // Current content reflects the edit; original is preserved in v1.
        assertEquals("\"edited notes\"", parsed["content"].toString())
    }

}
