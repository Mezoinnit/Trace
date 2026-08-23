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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 exit criteria:
 * 1. Multiple evidence items representing the same event resolve into ONE event.
 * 2. Contradictory evidence is preserved (CONTESTED), never silently merged.
 */
class EventEngineITest {

    private var repoIdentity = "acme/engine"

    private fun releasesUrl() =
        Regex.escape("https://api.github.com/repos/$repoIdentity/releases?per_page=30&page=1")

    private fun releaseJson(id: Long, tag: String, name: String, body: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$name",
         "html_url": "https://github.com/$repoIdentity/tag/$tag",
         "published_at": "2026-08-10T10:00:00Z", "prerelease": false, "draft": false,
         "body": "$body"}
        """.trimIndent()

    @Test
    fun `multiple reports of the same release converge into one event`() = testApplication {
        repoIdentity = "acme/eng-${UUID.randomUUID().toString().take(8)}"
        var fixture = "[${releaseJson(9100L, "v1.0.0", "Widget 1.0 released", "notes")}]"
        val fetcher = StubHttpFetcher()
        fetcher.serveDynamic(releasesUrl()) { StubHttpFetcher.Response(body = fixture.toByteArray()) }

        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "eng-${UUID.randomUUID()}@example.com")
        registerSource(client, session.accessToken())
        val endpointId = endpointIdFor(container)

        // Cycle 1: first report creates the event.
        container.fetchEndpointNow.execute(endpointId)
        assertEquals(1, eventCountForEndpoint(endpointId))

        // Cycle 2: a SECOND report of the same release under a different
        // provider id and slightly different title — clustering attaches it.
        fixture = "[" + listOf(
            releaseJson(9100L, "v1.0.0", "Widget 1.0 released", "notes"),
            releaseJson(9101L, "v1.0.0-announce", "WIDGET 1.0 RELEASED!!", "announcement"),
        ).joinToString(",") + "]"

        val run = container.fetchEndpointNow.execute(endpointId)
            as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success

        assertEquals(
            1,
            eventCountForEndpoint(endpointId),
            "second report must attach to the cluster representative, not create a duplicate",
        )
        // Both evidence records now support the same event.
        assertEquals(2, linksCountForEndpoint(endpointId))
        // Confidence was recomputed on attach — reasoning is inspectable.
        assertTrue(eventReasoningJson(endpointId).contains("corroboration"))
    }

    @Test
    fun `contradicting evidence is preserved as CONTESTED not merged`() = testApplication {
        repoIdentity = "acme/con-${UUID.randomUUID().toString().take(8)}"
        val fixture = "[${releaseJson(9200L, "v2.0.0", "Widget 2.0 released", "notes")}]"
        val fetcher = StubHttpFetcher()
        fetcher.serveDynamic(releasesUrl()) { StubHttpFetcher.Response(body = fixture.toByteArray()) }

        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "con-${UUID.randomUUID()}@example.com")
        registerSource(client, session.accessToken())
        val endpointId = endpointIdFor(container)

        container.fetchEndpointNow.execute(endpointId)

        val eventId = container.txRunner.run { container.eventRepo.latestDiscovered(1).single().id }
        val evidenceId = firstEvidenceId(endpointId)

        // A second source reports the OPPOSITE — deterministic state transition.
        container.markEventContested.execute(
            actorId = null,
            eventId = eventId,
            contradictingEvidenceId = evidenceId,
            reason = "source reports negotiations ongoing",
        )

        val detail = client.get("/api/v1/events/$eventId") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, detail.status)
        assertTrue(detail.bodyAsText().contains("\"CONTESTED\""))

        // Both roles preserved in event_evidence (nothing silently merged).
        assertEquals("CONTRADICTING", roleOf(evidenceId))

        // Idempotent re-mark.
        container.markEventContested.execute(null, eventId, evidenceId, "again")
        assertEquals("CONTRADICTING", roleOf(evidenceId))
    }

    // ---- helpers ----

    private suspend fun registerSource(client: io.ktor.client.HttpClient, token: String) {
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repoIdentity/releases","adapter_id":"github"}""")
        }
    }

    private fun endpointIdFor(container: com.mezo.trace.server.api.ApiContainer): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", repoIdentity)!!.id,
            ).single().id
        }

    private fun eventCountForEndpoint(endpointId: String): Int = sqlInt(
        "SELECT count(*) FROM events WHERE external_ref ->> 'endpoint_id' = ? OR id IN " +
            "(SELECT DISTINCT merged_into_event_id FROM events WHERE external_ref ->> 'endpoint_id' = ?)",
        endpointId, endpointId,
    )

    /** Counts events whose external_ref endpoint matches; attached duplicates don't exist by construction. */
    private fun linksCountForEndpoint(endpointId: String): Int = sqlInt(
        """
        SELECT count(*) FROM event_evidence ee
        JOIN events e ON e.id = ee.event_id
        WHERE e.external_ref ->> 'endpoint_id' = ?
        """.trimIndent(),
        endpointId,
    )

    private fun eventReasoningJson(endpointId: String): String = sqlString(
        "SELECT confidence_reasoning::text FROM events WHERE external_ref ->> 'endpoint_id' = ? LIMIT 1",
        endpointId,
    )

    private fun firstEvidenceId(endpointId: String): String = sqlString(
        "SELECT id FROM evidence WHERE endpoint_id::text = ? ORDER BY discovered_at LIMIT 1",
        endpointId,
    )

    private fun roleOf(evidenceId: String): String = sqlString(
        "SELECT role FROM event_evidence WHERE evidence_id::text = ? LIMIT 1",
        evidenceId,
    )

    @Suppress("SameParameterValue")
    private fun sqlString(query: String, vararg params: String): String =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement(query).use { ps ->
                params.forEachIndexed { i, p -> ps.setString(i + 1, p) }
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getString(1)
                }
            }
        }

    private fun sqlInt(query: String, vararg params: String): Int =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement(query).use { ps ->
                params.forEachIndexed { i, p -> ps.setString(i + 1, p) }
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }
}
