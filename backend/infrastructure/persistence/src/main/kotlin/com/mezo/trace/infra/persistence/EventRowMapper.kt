package com.mezo.trace.infra.persistence

import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import java.sql.ResultSet
import java.util.UUID

/**
 * Shared events row mapper used by intelligence and entity repositories.
 */
internal class EventRowMapper {
    fun fromRow(rs: ResultSet): Event = Event(
        id = rs.getObject("id", UUID::class.java).toString(),
        type = rs.getString("type"),
        title = rs.getString("title"),
        summary = rs.getObject("summary") as String?,
        occurredAt = rs.getObject("occurred_at")?.let { rs.instant("occurred_at") },
        discoveredAt = rs.instant("discovered_at"),
        confidence = rs.getString("confidence"),
        significance = rs.getString("significance"),
        status = EventStatus.valueOf(rs.getString("status")),
        externalRef = decodeRef(rs.getString("external_ref")),
        confidenceScore = rs.getBigDecimal("confidence_score")?.toDouble(),
        confidenceReasoning = decodeDoubleMap(rs.getString("confidence_reasoning")),
        mergedIntoEventId = rs.getObject("merged_into_event_id")
            ?.let { rs.getObject("merged_into_event_id", UUID::class.java).toString() },
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
    )

    private fun decodeRef(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            obj.entries.associate { (k, v) ->
                k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            }
        }.getOrDefault(emptyMap())
    }
}
