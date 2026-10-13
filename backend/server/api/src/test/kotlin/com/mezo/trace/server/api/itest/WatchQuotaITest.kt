package com.mezo.trace.server.api.itest

import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase 14 abuse prevention (Rule 6): per-workspace active-watch quota.
 * Every active watch is evaluated against every event, so a tenant must not
 * be able to grow watch count without bound.
 */
class WatchQuotaITest {

    @Test
    fun `creating watches beyond the workspace quota is rejected with 409`() = testApplication {
        val cap = 3
        val fetcher = com.mezo.trace.adapters.testing.StubHttpFetcher()
        val repoIdentity = "acme/quota-${UUID.randomUUID().toString().take(8)}"
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repoIdentity/releases?per_page=30&page=1"),
            com.mezo.trace.adapters.testing.StubHttpFetcher.Response(body = "[]".toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }

        val session = register(client, "quota-${UUID.randomUUID()}@example.com")
        val token = session.accessToken()

        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repoIdentity/releases","adapter_id":"github"}""")
        }

        // Rebuild the use case with a tiny cap for test speed.
        val createWatch = com.mezo.trace.application.usecase.watches.CreateWatch(
            container.watchRepo,
            container.sourceEndpoints,
            container.requireMembership,
            container.auditLog,
            container.txRunner,
            maxWatchesPerWorkspace = cap,
        )
        val workspaceId = session.workspaceId()
        val endpointId = container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", repoIdentity)!!.id,
            ).single().id
        }

        repeat(cap) { index ->
            createWatch.execute(
                com.mezo.trace.domain.UserId(session["user"]!!.let { (it as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"') }),
                workspaceId,
                "watch-$index",
                setOf(endpointId),
                emptySet(),
            )
        }

        // cap+1: the quota must reject with the conflict error even for a
        // legitimate member of the workspace.
        val thrown = kotlin.test.assertFailsWith<com.mezo.trace.application.AppError.Conflict> {
            createWatch.execute(
                com.mezo.trace.domain.UserId(session["user"]!!.let { (it as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"') }),
                workspaceId,
                "over-quota",
                setOf(endpointId),
                emptySet(),
            )
        }
        kotlin.test.assertTrue(
            thrown.message.contains("watch limit reached"),
            "error must explain the quota: ${thrown.message}",
        )
    }
}
