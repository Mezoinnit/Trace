package com.mezo.trace.server.api.itest

import com.mezo.trace.testsupport.EmbeddedPg
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuditTrailTest {

    private fun connection(): Connection = EmbeddedPg.dataSource().connection

    private fun auditCount(action: String, resourceId: String): Int =
        connection().prepareStatement(
            "SELECT count(*) FROM audit_log WHERE action = ? AND resource_id = ?",
        ).use { ps ->
            ps.setString(1, action)
            ps.setString(2, resourceId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    @Test
    fun `registration, rename and sign-in are audited`() = testApplication {
        app()
        val client = createClient { expectSuccess = false }

        val email = "audit-${UUID.randomUUID()}@example.com"
        val registered: JsonObject = register(client, email)
        val userId = (registered["user"] as JsonObject)["id"].toString().trim('"')
        val workspaceId = registered.workspaceId()
        val token = registered.accessToken()

        assertTrue(auditCount("USER_REGISTERED", userId) >= 1)
        assertTrue(auditCount("WORKSPACE_CREATED", workspaceId) >= 1)

        // Rename is audited.
        client.patch("/api/v1/workspaces/$workspaceId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Audited Rename"}""")
        }
        assertTrue(auditCount("WORKSPACE_UPDATED", workspaceId) >= 1)

        // Sign-in adds an audit row.
        signin(client, email)
        assertTrue(auditCount("USER_SIGNED_IN", userId) >= 1)
    }
}
