package com.mezo.trace.server.api.itest

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7 exit criteria, end to end: a Watch matches the CORRECT events —
 * keyword/threshold rules filter precisely — and never re-alerts on the same
 * event.
 */
class WatchEngineITest {

    private fun releaseJson(id: Long, tag: String, name: String, body: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$name",
         "html_url": "https://github.com/acme/$tag/releases/tag/$tag",
         "published_at": "2026-08-10T10:00:00Z", "prerelease": false,
         "draft": false, "body": "$body"}
        """.trimIndent()

    private var repoIdentity = "acme/we"

    private fun endpointUri() = "github://$repoIdentity/releases"

    @Test
    fun `keyword and threshold filters alert only on matching releases`() = testApplication {
        repoIdentity = "acme/we-${UUID.randomUUID().toString().take(8)}"
        val fixture = "[" + listOf(
            releaseJson(7100L, "v1.0.0", "Stable 1.0", "General improvements for everyone"),
            releaseJson(7101L, "v1.1.0-security", "Security patch 1.1", "Critical security fix for parser"),
            "\"placeholder\""
        ).joinToString(",") + "]"

        val fetcher = com.mezo.trace.adapters.testing.StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repoIdentity/releases?per_page=30&page=1"),
            com.mezo.trace.adapters.testing.StubHttpFetcher.Response(body = fixture.toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "we-${UUID.randomUUID()}@example.com")
        val token = session.accessToken()

        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"${endpointUri()}","adapter_id":"github"}""")
        }
        val endpointId = endpointIdOf(container)

        // Watch: only SECURITY-related, non-beta, at least MEDIUM significance.
        val watchResponse = client.post("/api/v1/watches") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"workspace_id":"${session.workspaceId()}","name":"security-only",
                    "endpoint_ids":["$endpointId"],
                    "keywords_include":["security"],"keywords_exclude":["beta"],
                    "min_significance":"MEDIUM"}""",
            )
        }
        assertEquals(io.ktor.http.HttpStatusCode.Created, watchResponse.status)

        // Cycle: three releases ingested.
        // - stable   → rejected (keyword_include miss: no "security")
        // - security → PASSES (include hit, no exclude hit, significance MEDIUM ≥ MEDIUM)
        // - beta     → rejected twice (exclude hit "beta"; significance LOW < MEDIUM)
        container.fetchEndpointNow.execute(endpointId)

        val wrow = container.txRunner.run { container.watchRepo.listForWorkspace(session.workspaceId()) }
        println("WATCH_ROW=" + wrow.firstOrNull()?.let { it.keywordsInclude.toString() + "/" + it.minSignificance })
        val alerts = alertTitles(container, session.workspaceId())
        assertEquals(1, alerts.size, "expected exactly the security release; got $alerts")
        assertTrue(alerts.single().contains("security", ignoreCase = true))

        // Replay: no duplicate alerts for the same events.
        container.fetchEndpointNow.execute(endpointId)
        assertEquals(1, alertCount(container, session.workspaceId()))
    }

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", repoIdentity)!!.id,
            ).single().id
        }

    private fun alertCount(container: com.mezo.trace.server.api.ApiContainer, workspaceId: String): Int =
        container.txRunner.run { container.alertRepo.listForWorkspace(workspaceId, limit = 100).size }

    private fun alertTitles(container: com.mezo.trace.server.api.ApiContainer, workspaceId: String): List<String> =
        container.txRunner.run {
            container.alertRepo.listForWorkspace(workspaceId, limit = 100).mapNotNull { alert ->
                container.eventRepo.findById(alert.eventId)?.title
            }
        }
}
