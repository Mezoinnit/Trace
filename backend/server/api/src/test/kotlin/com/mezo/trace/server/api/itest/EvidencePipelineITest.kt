package com.mezo.trace.server.api.itest

import com.mezo.trace.adapters.testing.StubHttpFetcher
import com.mezo.trace.server.api.ApiContainer
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

/**
 * Phase 3 exit criteria, end to end:
 * 1. The same external item fetched repeatedly does NOT create duplicate evidence.
 * 2. Content changes append versions; history is preserved (ADR-006).
 */
class EvidencePipelineITest {

    private fun releasesFixture(bodyText: String): String = """
        [
          {"id": 5001, "tag_name": "v3.0.0", "name": "Release Three",
           "html_url": "https://github.com/acme/widget/releases/tag/v3.0.0",
           "published_at": "2026-08-10T10:00:00Z", "prerelease": false, "draft": false,
           "body": "$bodyText"}
        ]
    """.trimIndent()

    @Test
    fun `refetching the same release never duplicates evidence`() = testApplication {
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/acme/widget/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = releasesFixture("Initial notes").toByteArray(), etag = "\"e1\""),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }

        val endpointId = registerSourceAndGetEndpointId(client, container)

        // First cycle: one evidence row.
        val firstRun = container.fetchEndpointNow.execute(endpointId) as ApiContainerSuccessShim
        assertEquals(1, firstRun.itemCount)

        // Second cycle with identical content: no new evidence.
        container.fetchEndpointNow.execute(endpointId)

        val count = evidenceCount(endpointId)
        assertEquals(1, count, "duplicate evidence created on refetch")
    }

    @Test
    fun `content changes append versions and preserve history`() = testApplication {
        var bodyText = "Initial notes"
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/acme/widget/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = releasesFixture(bodyText).toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val endpointId = registerSourceAndGetEndpointId(client, container)

        container.fetchEndpointNow.execute(endpointId)
        assertEquals(0, versionCount(endpointId), "no versions expected before any change")

        // Source changes previously published content → new version appended.
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/acme/widget/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = releasesFixture("Edited notes").toByteArray()),
        )
        container.fetchEndpointNow.execute(endpointId)

        assertEquals(1, versionCount(endpointId))
        // Original evidence row untouched; still exactly one item.
        assertEquals(1, evidenceCount(endpointId))
    }

    @Test
    fun `one fetch cycle backfills multiple cursor pages within the bound`() = testApplication {
        val fetcher = StubHttpFetcher()
        val page = { from: Long, count: Int ->
            (from until from + count).joinToString(",", "[", "]") { id ->
                """{"id": $id, "tag_name": "v0.$id", "name": null,
                    "html_url": "https://github.com/bk2/widget/releases/tag/v0.$id",
                    "published_at": "2026-08-01T10:00:00Z", "prerelease": false,
                    "draft": false, "body": "b"}"""
            }
        }
        val base = Regex.escape("https://api.github.com/repos/bk2/widget/releases?per_page=30&page=")
        fetcher.serve(base + "1", StubHttpFetcher.Response(body = page(1, 30).toByteArray()))
        fetcher.serve(base + "2", StubHttpFetcher.Response(body = page(31, 30).toByteArray()))
        fetcher.serve(base + "3", StubHttpFetcher.Response(body = page(61, 5).toByteArray())) // short page → stop

        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "bk-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://bk2/widget/releases","adapter_id":"github"}""")
        }
        val endpointId = container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", "bk2/widget")!!.id,
            ).single().id
        }

        val outcome = container.fetchEndpointNow.execute(endpointId)
            as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success

        assertEquals(65, outcome.evidenceCreated, "all pages ingested in one cycle")
        assertEquals(3, fetcher.requestedUrls.size, "bounded loop: page 1 + 2 follow-ups")

        // Replay: everything already stored, nothing new.
        container.fetchEndpointNow.execute(endpointId)
        assertEquals(65, evidenceCount(endpointId))
    }

    private suspend fun registerSourceAndGetEndpointId(
        client: io.ktor.client.HttpClient,
        container: ApiContainer,
    ): String {
        val email = "ev-${UUID.randomUUID()}@example.com"
        val session = register(client, email)
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://acme/widget/releases","adapter_id":"github"}""")
        }
        val (sourceId, endpointId) = container.txRunner.run {
            val source = container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", "acme/widget")!!
            source.id to container.sourceEndpoints.listBySourceId(source.id).single().id
        }
        return endpointId
    }

    private fun evidenceCount(endpointId: String): Int =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM evidence WHERE endpoint_id = ?").use { ps ->
                ps.setObject(1, java.util.UUID.fromString(endpointId))
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }

    private fun versionCount(endpointId: String): Int =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT count(*) FROM evidence_versions v
                JOIN evidence e ON e.id = v.evidence_id
                WHERE e.endpoint_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, java.util.UUID.fromString(endpointId))
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }
}

/** Local helper alias for readable assertions in this file. */
private typealias ApiContainerSuccessShim = com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success

private val ApiContainerSuccessShim.itemCount: Int get() = result.items.size