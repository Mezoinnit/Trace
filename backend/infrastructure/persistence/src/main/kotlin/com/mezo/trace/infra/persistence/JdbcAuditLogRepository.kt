package com.mezo.trace.infra.persistence

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import kotlinx.serialization.json.Json
import java.util.UUID

class JdbcAuditLogRepository(private val sql: SqlConnections) : AuditLogRepository {

    override fun append(entry: AuditEntry) {
        val workspaceUuid = entry.workspaceId?.let { id ->
            runCatching { UUID.fromString(id.value) }.getOrNull()
        }
        sql.current().prepareStatement(
            """
            INSERT INTO audit_log (id, workspace_id, actor_type, actor_id, action,
                                   resource_type, resource_id, metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            if (workspaceUuid != null) {
                ps.setObject(2, workspaceUuid)
            } else {
                ps.setNull(2, java.sql.Types.NULL)
            }
            ps.setString(3, entry.actorType.name)
            entry.actorId.let { value ->
                runCatching { UUID.fromString(value) }.getOrNull()
                    ?.let { ps.setObject(4, it) }
                    ?: ps.setNullableString(4, value)
            }
            ps.setString(5, entry.action)
            ps.setNullableString(6, entry.resourceType)
            ps.setNullableString(7, entry.resourceId)
            ps.setString(8, Json.encodeToString(MAP_SERIALIZER, entry.metadata))
            ps.executeUpdate()
        }
    }

    private companion object {
        val MAP_SERIALIZER = kotlinx.serialization.serializer<Map<String, String>>()
    }
}
