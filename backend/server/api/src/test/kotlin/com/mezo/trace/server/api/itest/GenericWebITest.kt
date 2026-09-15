package com.mezo.trace.server.api.itest

import com.mezo.trace.adapters.testing.StubHttpFetcher
import com.mezo.trace.domain.HealthState
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 13 exit criteria: user-provided website → controlled web adapter
 * produces evidence + ARTICLE_PUBLISHED, with parser health visible.
 */
class GenericWebITest {

    private val pageUrl = "https://news.example.com/tech"

    @Test
    fun `web page fetch yields article evidence events and visible parser health`() = testApplication {
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            "https://news.example.com/robots.txt",
            StubHttpFetcher.Response(contentType = "text/plain", body = "User-agent: *\nAllow: /\n".toByteArray()),
        )
        fetcher.serve(
            pageUrl,
            StubHttpFetcher.Response(
                contentType = "text/html",
                body = """
                    <html><head>
                      <link rel="canonical" href="$pageUrl"/>
                      <title>Tech</title>
                    </head><body>
                      <script type="application/ld+json">
                        {"@type":"NewsArticle","headline":"Robots learn chess",
                         "url":"https://news.example.com/robots-chess",
                         "datePublished":"2026-09-01T10:00:00Z",
                         "description":"A chess breakthrough."}
                      </script>
                    </body></html>
                """.trimIndent().toByteArray(),
                etag = "\"v1\"",
            ),
        )

        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val endpointId = registerWebPage(client, container, pageUrl)

        val outcome = container.fetchEndpointNow.execute(endpointId)
            as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success
        assertEquals(1, outcome.evidenceCreated)
        assertEquals(1, outcome.eventsDerived)
        assertTrue(outcome.result.notes.any { it.startsWith("json_ld_articles:") })
        assertEquals("WEB_PAGE", outcome.result.items.single().metadata["provider"])

        val (eventType, eventProvider) = articleEventFor(endpointId)
        assertEquals("ARTICLE_PUBLISHED", eventType)
        assertEquals("WEB_PAGE", eventProvider)

        val health = container.txRunner.run { container.sourceHealth.findByEndpointId(endpointId) }!!
        assertEquals(HealthState.HEALTHY, health.state)
        assertEquals(0, health.parserFailCount)
        assertEquals(null, health.lastParserError)
        // The extraction mode is visible even when extraction succeeded (Rule 7).
        assertTrue(health.lastParserNote!!.contains("json_ld_articles:"))
    }

    @Test
    fun `robots refusal surfaces PARSER_FAILED with notes on the health API`() = testApplication {
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            "https://blocked.example.com/robots.txt",
            StubHttpFetcher.Response(
                contentType = "text/plain",
                body = "User-agent: *\nDisallow: /\n".toByteArray(),
            ),
        )
        // Page must not be fetched when robots refuse — leave it unstubbed.

        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val email = "web-block-${UUID.randomUUID()}@example.com"
        val session = register(client, email)
        val page = "https://blocked.example.com/secret"
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"$page","adapter_id":"web_page"}""")
        }
        val (sourceId, endpointId) = container.txRunner.run {
            val source = container.sources.findByCanonicalIdentity("WEB_PAGE", "PAGE", page)!!
            source.id to container.sourceEndpoints.listBySourceId(source.id).single().id
        }

        container.fetchEndpointNow.execute(endpointId)

        val health = container.txRunner.run { container.sourceHealth.findByEndpointId(endpointId) }!!
        assertEquals(HealthState.PARSER_FAILED, health.state)
        assertTrue(health.parserFailCount >= 1)
        assertTrue(health.lastParserError!!.contains("robots_disallowed"))
        assertEquals("robots_disallowed", health.lastParserNote)

        val detail = client.get("/api/v1/sources/$sourceId") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }.bodyAsText()
        val healthJson = Json.parseToJsonElement(detail).jsonObject
            .getValue("endpoints").jsonArray.single().jsonObject
            .getValue("health").jsonObject
        assertEquals("PARSER_FAILED", healthJson.getValue("state").jsonPrimitive.content)
        assertTrue(healthJson.getValue("parser_fail_count").jsonPrimitive.content.toLong() >= 1)
        assertTrue(healthJson.getValue("last_parser_error").jsonPrimitive.content.contains("robots_disallowed"))
    }

    @Test
    fun `304 not_modified does not falsely mark parser failure`() = testApplication {
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            "https://stable.example.com/robots.txt",
            StubHttpFetcher.Response(contentType = "text/plain", body = ByteArray(0)),
        )
        fetcher.serve(
            "https://stable.example.com/",
            StubHttpFetcher.Response(
                statusCode = 304,
                contentType = "text/html",
                body = ByteArray(0),
                etag = "\"same\"",
            ),
        )

        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val endpointId = registerWebPage(client, container, "https://stable.example.com/")

        // Seed an etag so the adapter issues a conditional request.
        container.txRunner.run { container.sourceHealth.storeEtag(endpointId, "\"same\"") }

        container.fetchEndpointNow.execute(endpointId)

        val health = container.txRunner.run { container.sourceHealth.findByEndpointId(endpointId) }!!
        assertEquals(HealthState.HEALTHY, health.state)
        assertEquals(0, health.parserFailCount)
        assertEquals(null, health.lastParserError)
        // Conditional-poll cycles record their outcome without degrading health.
        assertEquals("not_modified", health.lastParserNote)
    }

    private suspend fun registerWebPage(
        client: io.ktor.client.HttpClient,
        container: ApiContainer,
        page: String,
    ): String {
        val session = register(client, "web-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"$page","adapter_id":"web_page"}""")
        }
        return container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("WEB_PAGE", "PAGE", page)!!.id,
            ).single().id
        }
    }

    private fun articleEventFor(endpointId: String): Pair<String, String> =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT e.type, e.external_ref->>'provider'
                FROM events e
                JOIN event_evidence ee ON ee.event_id = e.id
                JOIN evidence ev ON ev.id = ee.evidence_id
                WHERE ev.endpoint_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.fromString(endpointId))
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    rs.getString(1) to rs.getString(2)
                }
            }
        }
}
