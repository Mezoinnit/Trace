package com.mezo.trace.server.api.itest

import com.mezo.trace.adapters.testing.StubHttpFetcher
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 10 audit remediation R4: quiet sources become STALE (plan §9) and
 * recover to HEALTHY on the next successful fetch. Monitoring is never
 * silently stopped.
 */
class StaleHealthITest {

    @Test
    fun `quiet sources go STALE and recover on success`() = testApplication {
        val repo = "acme/stale-${UUID.randomUUID().toString().take(8)}"
        val fixture = """
            [{"id": 9950, "tag_name": "v0.1.0", "name": "v0.1.0",
              "html_url": "https://github.com/$repo/tag/v0.1.0",
              "published_at": "2026-08-10T10:00:00Z", "prerelease": false,
              "draft": false, "body": "n"}]
        """.trimIndent()
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = fixture.toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "st-${UUID.randomUUID()}@example.com")
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

        // One successful fetch → HEALTHY with last_success_at set.
        container.fetchEndpointNow.execute(endpointId)
        assertEquals("HEALTHY", stateOf(container, endpointId))

        // Source goes quiet beyond the staleness window.
        backdateLastSuccess(container, endpointId, hours = 72)

        // Scheduler pass marks it STALE (§9: never silently stop monitoring).
        container.scheduler.execute()
        assertEquals("STALE", stateOf(container, endpointId))

        // Next successful fetch restores HEALTHY.
        container.fetchEndpointNow.execute(endpointId)
        assertEquals("HEALTHY", stateOf(container, endpointId))
    }

    private fun stateOf(container: com.mezo.trace.server.api.ApiContainer, endpointId: String): String =
        container.txRunner.run { container.sourceHealth.findByEndpointId(endpointId)!!.state.name }

    private fun backdateLastSuccess(
        container: com.mezo.trace.server.api.ApiContainer,
        endpointId: String,
        hours: Long,
    ) {
        container.txRunner.run {
            container.sql.current().prepareStatement(
                "UPDATE source_health SET last_success_at = now() - (? || ' hours')::interval WHERE endpoint_id = ?::uuid",
            ).use { ps ->
                ps.setLong(1, hours)
                ps.setString(2, endpointId)
                ps.executeUpdate()
            }
        }
    }
}
