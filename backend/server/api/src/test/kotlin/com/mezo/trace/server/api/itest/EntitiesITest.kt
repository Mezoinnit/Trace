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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 6 exit criteria: different names/identifiers for the same entity
 * resolve consistently; distinct repositories never merge.
 */
class EntitiesITest {

    private fun releaseJson(id: Long, tag: String): String =
        """
        {"id": $id, "tag_name": "$tag", "name": "$tag",
         "html_url": "https://github.com/x/y/releases/tag/$tag",
         "published_at": "2026-08-10T10:00:00Z", "prerelease": false, "draft": false, "body": "n"}
        """.trimIndent()

    @Test
    fun `repository entities resolve by identifier and link events`() = testApplication {
        val repo = "acme/ent-${UUID.randomUUID().toString().take(8)}"
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = "[${releaseJson(9500L, "v1.0.0")}]" .toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "ent-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }
        val endpointId = endpointIdOf(container, repo)
        container.fetchEndpointNow.execute(endpointId)

        // Entity resolved deterministically from the structured identifier.
        val entity = container.txRunner.run {
            container.entityRepo.findByIdentifier("GITHUB_REPO", repo)
        }
        assertTrue(entity != null, "entity must be resolvable via GITHUB_REPO identifier")
        assertEquals("REPOSITORY", entity.type.name)

        // Alias resolves to the same entity.
        assertEquals(entity.id, container.txRunner.run { container.entityRepo.findByAlias(repo) }?.id)

        // Event linkage visible through the API with recent events attached.
        val detail = client.get("/api/v1/entities/${entity.id}") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, detail.status)
        assertTrue(detail.bodyAsText().contains("\"recent_events\""))
        assertTrue(detail.bodyAsText().contains(repo))
    }

    @Test
    fun `distinct repositories resolve to distinct entities`() {
        val repoA = "acme/dis-a-${UUID.randomUUID().toString().take(6)}"
        val repoB = "acme/dis-b-${UUID.randomUUID().toString().take(6)}"

        val idA = ingestAndGetEntityId(repoA)
        val idB = ingestAndGetEntityId(repoB)

        assertNotEquals(idA, idB)
    }

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer, repo: String): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", repo)!!.id,
            ).single().id
        }

    private fun ingestAndGetEntityId(repo: String): String {
        lateinit var entityId: String
        testApplication {
            val fetcher = StubHttpFetcher()
            fetcher.serve(
                Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
                StubHttpFetcher.Response(body = "[${releaseJson(System.nanoTime(), "v1.0.0")}]" .toByteArray()),
            )
            val container = app(fetcher = fetcher)
            val client = createClient { expectSuccess = false }
            val session = register(client, "ent-${UUID.randomUUID()}@example.com")
            client.post("/api/v1/sources") {
                header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
                contentType(ContentType.Application.Json)
                setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
            }
            val endpointId = endpointIdOf(container, repo)
            container.fetchEndpointNow.execute(endpointId)
            entityId = container.txRunner.run {
                container.entityRepo.findByIdentifier("GITHUB_REPO", repo)!!.id
            }
        }
        return entityId
    }
}
