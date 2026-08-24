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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 11 exit criterion: "What changed in X this month?" produces
 * structured intelligence — interpreted, filtered, entity-resolved — not a
 * flat article list.
 */
class SearchInvestigationITest {

    private fun releaseJson(id: Long, tag: String, name: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$name",
         "html_url": "https://github.com/x/y/tag/$tag",
         "published_at": "2026-08-20T10:00:00Z", "prerelease": false,
         "draft": false, "body": "notes for $name"}
        """.trimIndent()

    @Test
    fun `full-text search ranks and filters events`() = testApplication {
        val repo = "acme/srch-${UUID.randomUUID().toString().take(8)}"
        val fixture = "[" + listOf(
            releaseJson(9600L, "v1.0.0", "Compose compiler update"),
            releaseJson(9601L, "v1.1.0", "Kotlin 2.2 support"),
            releaseJson(9602L, "v1.2.0", "Compose animation fixes"),
        ).joinToString(",") + "]"
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = fixture.toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "srch-${UUID.randomUUID()}@example.com")
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

        // Full-text: compose matches two of three.
        val compose = client.get("/api/v1/search?q=compose") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, compose.status)
        assertTrue(compose.bodyAsText().contains("Compose"))
        assertTrue(compose.bodyAsText().contains("Kotlin 2.2 support").not())

        // Type filter still applies on top of FTS.
        val typed = client.get("/api/v1/search?q=compose&type=SOFTWARE_RELEASE") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertTrue(typed.bodyAsText().contains("Compose"))

        // Time window excludes everything (events dated 2026-08-20; window ends before).
        val windowed = client.get("/api/v1/search?q=compose&to=2026-08-01T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals("[]", Json.parseToJsonElement(windowed.bodyAsText()).jsonObjectSafe()["items"].toString())
    }

    @Test
    fun `investigation interprets question into structured results`() = testApplication {
        val repo = "acme/inv-${UUID.randomUUID().toString().take(8)}"
        val fixture = "[" + listOf(
            releaseJson(9650L, "v2.0.0", "Compose compiler update"),
            releaseJson(9651L, "v2.1.0", "Gradle plugin refresh"),
        ).joinToString(",") + "]"
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = fixture.toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "inv-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }

        // Seed the repository entity via alias so the hint resolves.
        container.txRunner.run {
            container.resolveEntity.execute(
                com.mezo.trace.application.usecase.entities.ResolveEntity.Request(
                    type = com.mezo.trace.domain.EntityType.REPOSITORY,
                    canonicalName = repo,
                    identifiers = listOf(com.mezo.trace.domain.EntityIdentifier("GITHUB_REPO", repo)),
                    aliases = listOf(repo),
                ),
            )
        }
        val endpointId = endpointIdOf(container, repo)
        container.fetchEndpointNow.execute(endpointId)

        val response = client.post("/api/v1/investigations") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"query":"What changed in $repo this month?"}""")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        // Subject-scoped: BOTH repository releases belong to the answer.
        assertTrue(body.contains("\"last_days\":30"))
        assertTrue(body.contains("Compose compiler update"))
        assertTrue(body.contains("Gradle plugin refresh"))
    }

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer, identity: String): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", identity)!!.id,
            ).single().id
        }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe() =
        this as kotlinx.serialization.json.JsonObject
}
