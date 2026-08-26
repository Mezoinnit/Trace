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

    override fun listForResource(
        resourceType: String,
        resourceId: String,
    ): List<com.mezo.trace.application.port.EventHistoryEntry> =
        sql.current().prepareStatement(
            """
            SELECT action, metadata::text AS metadata_text, created_at
            FROM audit_log
            WHERE resource_type = ? AND resource_id = ?
            ORDER BY created_at
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, resourceType)
            ps.setString(2, resourceId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<com.mezo.trace.application.port.EventHistoryEntry>()
                while (rs.next()) {
                    val metaText = rs.getString("metadata_text").orEmpty()
                    val meta: Map<String, String> = runCatching {
                        val obj = kotlinx.serialization.json.Json.parseToJsonElement(metaText)
                            as kotlinx.serialization.json.JsonObject
                        obj.entries.associate { (k, v) ->
                            k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        }
                    }.getOrDefault(emptyMap())
                    out += com.mezo.trace.application.port.EventHistoryEntry(
                        at = rs.instant("created_at"),
                        action = rs.getString("action"),
                        detail = meta,
                    )
                }
                out
            }
        }
}
