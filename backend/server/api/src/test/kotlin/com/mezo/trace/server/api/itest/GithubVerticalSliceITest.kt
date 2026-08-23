package com.mezo.trace.server.api.itest

import com.mezo.trace.adapters.testing.StubHttpFetcher
import io.ktor.client.request.delete
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 4 exit criterion, end to end over HTTP:
 * a GitHub repository can be monitored continuously and each new release
 * produces **exactly one** correct alert per watching workspace — replays
 * never duplicate (ADR-012).
 */
class GithubVerticalSliceITest {

    private var repoIdentity = "acme/widget"
    private fun releasesUrl() = Regex.escape("https://api.github.com/repos/$repoIdentity/releases?per_page=30&page=1")
    private fun endpointUri() = "github://$repoIdentity/releases"

    private fun releasesJson(vararg releases: Triple<Long, String, String>): String =
        releases.joinToString(",", "[", "]") { (id, tag, body) ->
            """
            {"id": $id, "tag_name": "$tag", "name": "$tag",
             "html_url": "https://github.com/$repoIdentity/tag/$tag",
             "published_at": "2026-08-10T10:00:00Z", "prerelease": false, "draft": false,
             "body": "$body"}
            """.trimIndent()
        }

    @Test
    fun `new releases produce exactly one alert per watch and replays never duplicate`() = testApplication {
        repoIdentity = "acme/slice-one"
        val releasesUrl = releasesUrl()
        var fixture = releasesJson(Triple(7001L, "v3.0.0", "Initial stable"))
        val fetcher = StubHttpFetcher()
        fetcher.serveDynamic(releasesUrl) {
            StubHttpFetcher.Response(body = fixture.toByteArray(), etag = null)
        }
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }

        // User A registers the source and watches it.
        val sessionA = register(client, "a-${UUID.randomUUID()}@example.com")
        val tokenA = sessionA.accessToken()
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"${endpointUri()}","adapter_id":"github"}""")
        }
        val sourceId = sourceIdFor(container, repoIdentity)
        val endpointId = container.txRunner.run {
            container.sourceEndpoints.listBySourceId(sourceId).single().id
        }
        val watchId = createWatch(client, tokenA, sessionWorkspaceId(sessionA), endpointId)

        // Cycle 1: one release → one event → one alert.
        val run1 = container.fetchEndpointNow.execute(endpointId)
        assertTrue(run1 is com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success)
        run1 as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success
        assertEquals(1, run1.evidenceCreated)
        assertEquals(1, run1.eventsDerived)
        assertEquals(1, run1.alertsCreated)
        assertEquals(1, alertCount(container, sessionA), "exactly one alert after cycle 1")

        // Replay of cycle 1: nothing duplicates anywhere.
        container.fetchEndpointNow.execute(endpointId)
        assertEquals(1, alertCount(container, sessionA))
        assertEquals(1, eventCountForEndpoint(endpointId))

        // Cycle 2: a NEW release appears alongside the old one.
        fixture = releasesJson(
            Triple(7001L, "v3.0.0", "Initial stable"),
            Triple(7002L, "v3.1.0", "Follow-up release"),
        )
        val run2 = container.fetchEndpointNow.execute(endpointId)
        run2 as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success
        assertEquals(1, run2.evidenceCreated, "only the new release becomes evidence")
        assertEquals(1, run2.eventsDerived)
        assertEquals(1, run2.alertsCreated, "only the new release alerts")
        assertEquals(2, alertCount(container, sessionA))

        // Deleting the watch stops further alerts for user A.
        client.delete("/api/v1/watches/$watchId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        fixture = releasesJson(
            Triple(7001L, "v3.0.0", "Initial stable"),
            Triple(7002L, "v3.1.0", "Follow-up release"),
            Triple(7003L, "v3.2.0", "Third release"),
        )
        val run3 = container.fetchEndpointNow.execute(endpointId)
        run3 as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success
        assertEquals(1, run3.evidenceCreated)
        assertEquals(0, run3.alertsCreated, "no alerts without an active watch")

        // Events feed is cursor-paginated (plan §28).
        val page1 = Json.parseToJsonElement(
            client.get("/api/v1/events?limit=2") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
            }.bodyAsText(),
        ).jsonObjectSafe()
        assertEquals(2, (page1["items"] as kotlinx.serialization.json.JsonArray).size)
        assertNotNull(page1["next_cursor"])
    }

    @Test
    fun `two workspaces watching the same endpoint each get their own alerts`() = testApplication {
        repoIdentity = "acme/slice-two"
        val fixture = releasesJson(Triple(8001L, "v9.9.9", "Shared release"))
        val fetcher = StubHttpFetcher()
        fetcher.serve(releasesUrl(), StubHttpFetcher.Response(body = fixture.toByteArray()))
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }

        val sessionA = register(client, "a-${UUID.randomUUID()}@example.com")
        val sessionB = register(client, "b-${UUID.randomUUID()}@example.com")
        registerSource(client, sessionA.accessToken())
        val endpointId = container.txRunner.run {
            val sourceId = sourceIdFor(container, repoIdentity)
            container.sourceEndpoints.listBySourceId(sourceId).single().id
        }
        createWatch(client, sessionA.accessToken(), sessionWorkspaceId(sessionA), endpointId)
        createWatch(client, sessionB.accessToken(), sessionWorkspaceId(sessionB), endpointId)

        container.fetchEndpointNow.execute(endpointId)

        assertEquals(1, alertCount(container, sessionA))
        assertEquals(1, alertCount(container, sessionB))
        // One ingestion fans out to both watchers (ADR-001): single evidence row.
        assertEquals(1, eventCountForEndpoint(endpointId))
    }

    private suspend fun createWatch(
        client: io.ktor.client.HttpClient,
        token: String,
        workspaceId: String,
        endpointId: String,
    ): String {
        val response = client.post("/api/v1/watches") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"workspace_id":"$workspaceId","name":"Widget releases",
                    "endpoint_ids":["$endpointId"],"event_types":["SOFTWARE_RELEASE"]}""",
            )
        }
        assertEquals(io.ktor.http.HttpStatusCode.Created, response.status)
        return Regex("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").find(response.bodyAsText())!!.groupValues[1]
    }

    private suspend fun registerSource(client: io.ktor.client.HttpClient, token: String) {
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"${endpointUri()}","adapter_id":"github"}""")
        }
    }

    private fun sourceIdFor(container: com.mezo.trace.server.api.ApiContainer, identity: String): String =
        container.txRunner.run {
            container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", identity)!!.id
        }

    private fun sessionWorkspaceId(session: kotlinx.serialization.json.JsonObject): String =
        (session["workspace"] as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"')

    private fun alertCount(
        container: com.mezo.trace.server.api.ApiContainer,
        session: kotlinx.serialization.json.JsonObject,
    ): Int {
        val workspaceId = sessionWorkspaceId(session)
        return container.txRunner.run {
            container.alertRepo.listForWorkspace(workspaceId, limit = 100).size
        }
    }

    private fun eventCountForEndpoint(endpointId: String): Int =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM events WHERE external_ref ->> 'endpoint_id' = ?").use { ps ->
                ps.setString(1, endpointId)
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe() =
        this as kotlinx.serialization.json.JsonObject
}
