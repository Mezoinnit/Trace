package com.mezo.trace.server.api.itest

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 0–6 audit remediation tests:
 * - A3: account deletion semantics (plan §26)
 * - A1: entity false-merge recovery (Phase 6 exit criterion)
 * - A2: watch GET/PATCH with optimistic locking + lifecycle audit (§43, §24)
 */
class AuditRemediationITest {

    private fun uniqueEmail() = "fix-${UUID.randomUUID()}@example.com"

    private fun userIdOf(session: JsonObject): String =
        (session["user"] as JsonObject)["id"].toString().trim('"')

    // ---- A3: account deletion ----

    @Test
    fun `account deletion cascades and revokes sessions`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        val session = register(client, uniqueEmail())
        val token = session.accessToken()
        val userId = userIdOf(session)
        val workspaceId = session.workspaceId()

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status)

        // Sessions revoked: the old access token no longer authenticates.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status)

        // Refresh tokens revoked too.
        val refreshed = client.postJson(
            "/api/v1/auth/refresh",
            """{"refresh_token":"${session.refreshToken()}"}""",
        )
        assertEquals(HttpStatusCode.Unauthorized, refreshed.status)

        // Personal workspace gone.
        val conn = com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection
        conn.prepareStatement("SELECT count(*) FROM workspaces WHERE id::text = ?").use { ps ->
            ps.setString(1, workspaceId)
            ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
        }
    }

    @Test
    fun `account deletion refuses while sharing a multi-member workspace`() = testApplication {
        val container = app()
        val client = createClient { expectSuccess = false }
        val session = register(client, uniqueEmail())
        val token = session.accessToken()

        // Construct a shared membership directly (invites arrive in a later phase).
        val otherWsId = UUID.randomUUID().toString()
        val peerId = com.mezo.trace.domain.UserId(UUID.randomUUID().toString())
        container.txRunner.run {
            container.users.insert(
                com.mezo.trace.domain.User(
                    peerId,
                    com.mezo.trace.domain.Email.create("peer-\${UUID.randomUUID()}@example.com"),
                    null, "x", java.time.Instant.now(), java.time.Instant.now(),
                ),
            )
            container.workspaces.insert(
                com.mezo.trace.domain.Workspace(
                    com.mezo.trace.domain.WorkspaceId(otherWsId), "Shared", peerId,
                    java.time.Instant.now(), java.time.Instant.now(),
                ),
            )
            container.memberships.insert(
                com.mezo.trace.domain.WorkspaceMember(
                    com.mezo.trace.domain.WorkspaceId(otherWsId),
                    com.mezo.trace.domain.UserId(userIdOf(session)),
                    com.mezo.trace.domain.Role.MEMBER,
                    java.time.Instant.now(),
                ),
            )
            container.memberships.insert(
                com.mezo.trace.domain.WorkspaceMember(
                    com.mezo.trace.domain.WorkspaceId(otherWsId), peerId,
                    com.mezo.trace.domain.Role.OWNER, java.time.Instant.now(),
                ),
            )
        }

        val response = client.delete("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("shared"))
    }

    @Test
    fun `account deletion requires authentication`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/me").status)
    }

    // ---- A1: entity merge recovery ----

    @Test
    fun `entity merge repoints lookups and is replay-safe`() = testApplication {
        val container = app()
        val client = createClient { expectSuccess = false }
        val session = register(client, uniqueEmail())

        val suffix = UUID.randomUUID().toString().take(8)
        val loser = container.txRunner.run {
            container.resolveEntity.execute(
                com.mezo.trace.application.usecase.entities.ResolveEntity.Request(
                    type = com.mezo.trace.domain.EntityType.REPOSITORY,
                    canonicalName = "acme/dup-$suffix",
                    identifiers = listOf(com.mezo.trace.domain.EntityIdentifier("GITHUB_REPO", "acme/dup-$suffix")),
                ),
            ).id
        }
        val survivor = container.txRunner.run {
            container.resolveEntity.execute(
                com.mezo.trace.application.usecase.entities.ResolveEntity.Request(
                    type = com.mezo.trace.domain.EntityType.REPOSITORY,
                    canonicalName = "acme/can-$suffix",
                    identifiers = listOf(com.mezo.trace.domain.EntityIdentifier("GITHUB_REPO", "acme/can-$suffix")),
                ),
            ).id
        }

        val merge = client.post("/api/v1/entities/$loser/merge") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"target_id":"$survivor"}""")
        }
        assertEquals(HttpStatusCode.NoContent, merge.status)

        // Old identifier now resolves to the survivor.
        val resolved = container.txRunner.run {
            container.entityRepo.findByIdentifier("GITHUB_REPO", "acme/dup-$suffix")
        }
        assertEquals(survivor, resolved?.id)

        // Loser keeps its row with a recovery pointer.
        val loserRow = container.txRunner.run { container.entityRepo.findById(loser)!! }
        assertEquals(survivor, loserRow.mergedIntoEntityId)

        // Replay of the same merge is accepted (idempotent).
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/entities/$loser/merge") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"target_id":"$survivor"}""")
        }.status)

        // Re-parenting elsewhere is rejected.
        val third = container.txRunner.run {
            container.resolveEntity.execute(
                com.mezo.trace.application.usecase.entities.ResolveEntity.Request(
                    type = com.mezo.trace.domain.EntityType.REPOSITORY,
                    canonicalName = "acme/third-$suffix",
                ),
            ).id
        }
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/entities/$loser/merge") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"target_id":"$third"}""")
        }.status)
    }

    // ---- A2: watch surface + optimistic locking + audit ----

    @Test
    fun `watch get patch with version conflict detection and audit trail`() = testApplication {
        val container = app()
        val client = createClient { expectSuccess = false }
        val session = register(client, uniqueEmail())
        val token = session.accessToken()

        // Endpoint to watch.
        val suffix = UUID.randomUUID().toString().take(8)
        container.txRunner.run {
            container.createSource.execute(
                com.mezo.trace.domain.UserId(userIdOf(session)),
                "github://acme/wt-$suffix/releases",
                "github",
                null,
            )
        }
        val endpointId = endpointIdOf(container, "acme/wt-$suffix")

        val created = Json.parseToJsonElement(
            client.post("/api/v1/watches") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"workspace_id":"${session.workspaceId()}","name":"w",
                        "endpoint_ids":["$endpointId"],"event_types":[]}""",
                )
            }.bodyAsText(),
        ).jsonObject
        val watchId = created["id"].toString().trim('"')
        val versionV1 = created["version"].toString().toInt()

        // GET by id works for members.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/watches/$watchId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status)

        // PATCH with correct version bumps it.
        val patched = client.patch("/api/v1/watches/$watchId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"expected_version":$versionV1,"name":"renamed"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val versionV2 = Json.parseToJsonElement(patched.bodyAsText()).jsonObject["version"].toString().toInt()
        assertEquals(versionV1 + 1, versionV2)

        // Stale version conflicts (optimistic locking, plan §24).
        val conflict = client.patch("/api/v1/watches/$watchId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"expected_version":$versionV1,"name":"stale-write"}""")
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)

        // Lifecycle audit records exist (plan §43).
        assertNotEquals(0, auditCount("WATCH_CREATED", watchId))
        assertEquals(1, auditCount("WATCH_UPDATED", watchId))

        client.delete("/api/v1/watches/$watchId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(1, auditCount("WATCH_DELETED", watchId))
    }

    // ---- helpers ----

    private fun endpointIdOf(container: com.mezo.trace.server.api.ApiContainer, identity: String): String =
        container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", identity)!!.id,
            ).single().id
        }

    private fun auditCount(action: String, resourceId: String): Int =
        com.mezo.trace.testsupport.EmbeddedPg.dataSource().connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM audit_log WHERE action = ? AND resource_id = ?",
            ).use { ps ->
                ps.setString(1, action)
                ps.setString(2, resourceId)
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }
}
