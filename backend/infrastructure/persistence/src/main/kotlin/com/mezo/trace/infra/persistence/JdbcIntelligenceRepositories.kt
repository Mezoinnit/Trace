package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.AlertRepository
import com.mezo.trace.application.port.EventRepository
import com.mezo.trace.application.port.WatchRepository
import com.mezo.trace.domain.Alert
import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import com.mezo.trace.domain.Watch
import java.sql.ResultSet
import java.util.Base64
import java.time.Instant
import java.util.UUID

class JdbcEventRepository(private val sql: SqlConnections) : EventRepository {

    override fun findByExternalRef(provider: String, externalId: String): Event? =
        sql.current().prepareStatement(
            """
            SELECT * FROM events
            WHERE external_ref ->> 'provider' = ?
              AND external_ref ->> 'external_id' = ?
            LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, externalId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEvent() else null }
        }

    override fun insert(event: Event): Boolean =
        sql.current().prepareStatement(
            """
            INSERT INTO events (id, type, title, summary, occurred_at, discovered_at,
                                confidence, significance, status, external_ref, created_at, updated_at,
                                confidence_score, confidence_reasoning, merged_into_event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(event.id))
            ps.setString(2, event.type)
            ps.setString(3, event.title)
            ps.setNullableString(4, event.summary)
            val occurredAt = event.occurredAt
            if (occurredAt != null) ps.setInstant(5, occurredAt) else ps.setNull(5, java.sql.Types.TIMESTAMP)
            ps.setInstant(6, event.discoveredAt)
            ps.setString(7, event.confidence)
            ps.setString(8, event.significance)
            ps.setString(9, event.status.name)
            ps.setString(10, refJson(event.externalRef))
            ps.setInstant(11, event.createdAt)
            ps.setInstant(12, event.updatedAt)
            val score = event.confidenceScore
            if (score != null) ps.setDouble(13, score) else ps.setNull(13, java.sql.Types.NUMERIC)
            ps.setString(14, doubleMapJson(event.confidenceReasoning))
            if (event.mergedIntoEventId != null) ps.setObject(15, UUID.fromString(event.mergedIntoEventId)) else ps.setNull(15, java.sql.Types.NULL)
            ps.executeUpdate() > 0
        }

    override fun linkEvidence(eventId: String, evidenceId: String, role: String) {
        sql.current().prepareStatement(
            """
            INSERT INTO event_evidence (event_id, evidence_id, role)
            VALUES (?, ?, ?)
            ON CONFLICT (event_id, evidence_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(eventId))
            ps.setObject(2, UUID.fromString(evidenceId))
            ps.setString(3, role)
            ps.executeUpdate()
        }
    }

    override fun evidenceForEvent(eventId: String): List<com.mezo.trace.application.port.EventEvidenceView> {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            """
            SELECT ev.id AS evidence_id, ev.title, ev.canonical_url, ee.role,
                   ev.published_at, s.display_name AS source_name, s.trust_classification
            FROM event_evidence ee
            JOIN evidence ev ON ev.id = ee.evidence_id
            JOIN sources s ON s.id = ev.source_id
            WHERE ee.event_id = ?
            ORDER BY ee.created_at
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<com.mezo.trace.application.port.EventEvidenceView>()
                while (rs.next()) {
                    out += com.mezo.trace.application.port.EventEvidenceView(
                        evidenceId = rs.getObject("evidence_id", UUID::class.java).toString(),
                        title = rs.getString("title"),
                        canonicalUrl = rs.getString("canonical_url"),
                        role = rs.getString("role"),
                        sourceName = rs.getString("source_name"),
                        sourceTrust = rs.getString("trust_classification"),
                        publishedAt = rs.getObject("published_at")?.let { rs.instant("published_at") },
                    )
                }
                out
            }
        }
    }

    override fun relatedEvents(eventId: String, limit: Int): List<Event> {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            """
            SELECT * FROM (
                SELECT DISTINCT e.*
                FROM events e
                JOIN event_entities ee ON ee.event_id = e.id
                WHERE ee.entity_id IN (
                    SELECT entity_id FROM event_entities WHERE event_id = ?::uuid
                )
                  AND e.id <> ?::uuid
                  AND e.merged_into_event_id IS NULL
            ) rel
            ORDER BY COALESCE(rel.occurred_at, rel.discovered_at) DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, eventId)
            ps.setString(2, eventId)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out += rs.toEvent()
                out
            }
        }
    }

    override fun updateStatus(eventId: String, status: com.mezo.trace.domain.EventStatus) {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return
        sql.current().prepareStatement(
            "UPDATE events SET status = ?, updated_at = now() WHERE id = ?",
        ).use { ps ->
            ps.setString(1, status.name)
            ps.setObject(2, uuid)
            ps.executeUpdate()
        }
    }

    override fun markEvidenceRole(evidenceId: String, eventId: String, role: String) {
        sql.current().prepareStatement(
            """
            INSERT INTO event_evidence (event_id, evidence_id, role)
            VALUES (?, ?, ?)
            ON CONFLICT (event_id, evidence_id) DO UPDATE SET role = EXCLUDED.role
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(eventId))
            ps.setObject(2, UUID.fromString(evidenceId))
            ps.setString(3, role)
            ps.executeUpdate()
        }
    }

    override fun countDistinctSupportingEndpoints(eventId: String): Int {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return 0
        return sql.current().prepareStatement(
            """
            SELECT count(DISTINCT e.endpoint_id)
            FROM event_evidence ee JOIN evidence e ON e.id = ee.evidence_id
            WHERE ee.event_id = ? AND ee.role = 'SUPPORTING'
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    override fun updateConfidence(
        eventId: String,
        band: String,
        score: Double,
        reasoning: Map<String, Double>,
    ) {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return
        sql.current().prepareStatement(
            "UPDATE events SET confidence = ?, confidence_score = ?, confidence_reasoning = ?::jsonb, updated_at = now() WHERE id = ?",
        ).use { ps ->
            ps.setString(1, band)
            ps.setDouble(2, score)
            ps.setString(3, doubleMapJson(reasoning))
            ps.setObject(4, uuid)
            ps.executeUpdate()
        }
    }

    override fun findById(id: String): Event? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return sql.current().prepareStatement("SELECT * FROM events WHERE id = ?").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEvent() else null }
        }
    }

    override fun feedPage(cursor: String?, limit: Int): com.mezo.trace.application.port.EventFeedPage {
        val decoded = decodeCursor(cursor)
        val events = sql.current().prepareStatement(
            if (decoded == null) {
                "SELECT * FROM events ORDER BY occurred_at DESC NULLS LAST, id DESC LIMIT ?"
            } else {
                """
                SELECT * FROM events
                WHERE (occurred_at DESC NULLS LAST, id DESC) < (?::timestamptz, ?::uuid)
                ORDER BY occurred_at DESC NULLS LAST, id DESC
                LIMIT ?
                """.trimIndent()
            },
        ).use { ps ->
            if (decoded == null) {
                ps.setInt(1, limit)
            } else {
                ps.setString(1, decoded.first)
                ps.setObject(2, UUID.fromString(decoded.second))
                ps.setInt(3, limit)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out += rs.toEvent()
                out
            }
        }
        val nextCursor = events.lastOrNull()
            ?.takeIf { events.size == limit }
            ?.let { encodeCursor(it) }
        return com.mezo.trace.application.port.EventFeedPage(events, nextCursor)
    }

    override fun latestDiscovered(limit: Int): List<Event> =
        sql.current().prepareStatement(
            "SELECT * FROM events ORDER BY discovered_at DESC, id DESC LIMIT ?",
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out += rs.toEvent()
                out
            }
        }

    override fun searchEvents(
        query: String,
        filters: com.mezo.trace.application.port.EventSearchFilters,
        limit: Int,
    ): List<Event> {
        // websearch_to_tsquery tolerates natural phrasing; empty queries fall
        // through to filter-only scans, still bounded.
        val conditions = StringBuilder("1 = 1")
        val params = mutableListOf<Any>()

        if (query.isNotBlank()) {
            conditions.append(" AND search_vec @@ websearch_to_tsquery('english', ?)")
            params.add(query.trim())
        }
        filters.type?.let { t ->
            conditions.append(" AND type = ?")
            params.add(t)
        }
        filters.from?.let {
            conditions.append(" AND COALESCE(occurred_at, discovered_at) >= ?")
            params.add(java.time.OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC))
        }
        filters.to?.let {
            conditions.append(" AND COALESCE(occurred_at, discovered_at) <= ?")
            params.add(java.time.OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC))
        }
        val filterSourceId = filters.sourceId
        if (filterSourceId != null) {
            conditions.append(
                " AND EXISTS (SELECT 1 FROM event_evidence ee2 JOIN evidence e2 ON e2.id = ee2.evidence_id " +
                    "WHERE ee2.event_id = events.id AND e2.source_id = ?::uuid)",
            )
            params.add(filterSourceId)
        }
        val filterEntityId = filters.entityId
        if (filterEntityId != null) {
            conditions.append(
                " AND EXISTS (SELECT 1 FROM event_entities ent WHERE ent.event_id = events.id AND ent.entity_id = ?::uuid)",
            )
            params.add(filterEntityId)
        }

        var rankClause = ""
        if (query.isNotBlank()) {
            rankClause = ", ts_rank(search_vec, websearch_to_tsquery('english', ?)) DESC"
            params.add(query.trim())
        }
        params.add(limit)

        val sqlText = """
            SELECT * FROM events
            WHERE $conditions
            ORDER BY COALESCE(occurred_at, discovered_at) DESC$rankClause
            LIMIT ?
        """.trimIndent()

        return sql.current().prepareStatement(sqlText).use { ps ->
            params.forEachIndexed { i, value ->
                when (value) {
                    is String -> ps.setString(i + 1, value)
                    is java.time.OffsetDateTime -> ps.setObject(i + 1, value)
                    is Int -> ps.setInt(i + 1, value)
                }
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out += rs.toEvent()
                out
            }
        }
    }

    override fun latestDiscoveredBySource(sourceId: String, limit: Int): List<Event> {
        val uuid = runCatching { java.util.UUID.fromString(sourceId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            """
            SELECT DISTINCT e.*
            FROM events e
            JOIN event_evidence ee ON ee.event_id = e.id
            JOIN evidence ev ON ev.id = ee.evidence_id
            WHERE ev.source_id = ? AND e.merged_into_event_id IS NULL
            ORDER BY e.discovered_at DESC, e.id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out += rs.toEvent()
                out
            }
        }
    }

    private fun encodeCursor(event: Event): String =
        Base64.getEncoder().encodeToString(
            "${event.occurredAt ?: event.discoveredAt}|${event.id}".toByteArray(),
        )

    private fun decodeCursor(cursor: String?): Pair<String, String>? {
        if (cursor.isNullOrBlank()) return null
        val raw = runCatching { String(Base64.getDecoder().decode(cursor)) }.getOrNull() ?: return null
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    internal fun cursorOf(event: Event): String = encodeCursor(event)

    private fun refJson(ref: Map<String, String>): String =
        kotlinx.serialization.json.buildJsonObject {
            ref.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }.toString()

    private fun ResultSet.toEvent(): Event = EVENT_MAPPER.fromRow(this)

    private fun decodeRef(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            obj.entries.associate { (k, v) -> k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        }.getOrDefault(emptyMap())
    }
}

class JdbcWatchRepository(private val sql: SqlConnections) : WatchRepository {

    override fun insert(watch: Watch) {
        sql.current().prepareStatement(
            """
            INSERT INTO watches (id, workspace_id, created_by, name, endpoint_ids, event_types,
                                 entity_ids, keywords_include, keywords_exclude, semantic,
                                 max_age_hours, min_significance, min_confidence,
                                 notification_mode, status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(watch.id))
            ps.setObject(2, UUID.fromString(watch.workspaceId))
            ps.setObject(3, UUID.fromString(watch.createdBy))
            ps.setString(4, watch.name)
            ps.setString(5, listJson(watch.endpointIds.toList()))
            ps.setString(6, listJson(watch.eventTypes.toList()))
            ps.setString(7, listJson(watch.entityIds.toList()))
            ps.setString(8, listJson(watch.keywordsInclude))
            ps.setString(9, listJson(watch.keywordsExclude))
            ps.setNullableString(10, watch.semantic)
            val maxAgeInsert = watch.maxAgeHours
            if (maxAgeInsert != null) ps.setInt(11, maxAgeInsert) else ps.setNull(11, java.sql.Types.INTEGER)
            ps.setNullableString(12, watch.minSignificance)
            ps.setNullableString(13, watch.minConfidence)
            ps.setString(14, watch.notificationMode.name)
            ps.setString(15, watch.status.name)
            ps.setInt(16, watch.version)
            ps.setInstant(17, watch.createdAt)
            ps.setInstant(18, watch.updatedAt)
            ps.executeUpdate()
        }
    }

    override fun findById(id: String): Watch? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return sql.current().prepareStatement("SELECT * FROM watches WHERE id = ? AND status <> 'DELETED'").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toWatch() else null }
        }
    }

    override fun listForWorkspace(workspaceId: String): List<Watch> =
        sql.current().prepareStatement(
            "SELECT * FROM watches WHERE workspace_id = ? AND status <> 'DELETED' ORDER BY created_at DESC",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(workspaceId))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Watch>()
                while (rs.next()) out += rs.toWatch()
                out
            }
        }

    override fun findActiveForEndpoint(endpointId: String): List<Watch> =
        // jsonb '?' would collide with JDBC placeholders; jsonb_exists is the
        // callable equivalent and works for JSON arrays of strings.
        sql.current().prepareStatement(
            """
            SELECT * FROM watches
            WHERE status = 'ACTIVE'
              AND jsonb_exists(endpoint_ids, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, endpointId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Watch>()
                while (rs.next()) out += rs.toWatch()
                out
            }
        }

    override fun setStatus(id: String, status: String, now: Instant) {
        sql.current().prepareStatement("UPDATE watches SET status = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, status)
            ps.setInstant(2, now)
            ps.setObject(3, UUID.fromString(id))
            ps.executeUpdate()
        }
    }

    override fun update(watch: Watch) {
        sql.current().prepareStatement(
            """
            UPDATE watches SET name = ?, endpoint_ids = ?::jsonb, event_types = ?::jsonb,
                               entity_ids = ?::jsonb, keywords_include = ?::jsonb,
                               keywords_exclude = ?::jsonb, semantic = ?,
                               max_age_hours = ?, min_significance = ?, min_confidence = ?,
                               notification_mode = ?, status = ?, version = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, watch.name)
            ps.setString(2, listJson(watch.endpointIds.toList()))
            ps.setString(3, listJson(watch.eventTypes.toList()))
            ps.setString(4, listJson(watch.entityIds.toList()))
            ps.setString(5, listJson(watch.keywordsInclude))
            ps.setString(6, listJson(watch.keywordsExclude))
            ps.setNullableString(7, watch.semantic)
            val maxAgeUpdate = watch.maxAgeHours
            if (maxAgeUpdate != null) ps.setInt(8, maxAgeUpdate) else ps.setNull(8, java.sql.Types.INTEGER)
            ps.setNullableString(9, watch.minSignificance)
            ps.setNullableString(10, watch.minConfidence)
            ps.setString(11, watch.notificationMode.name)
            ps.setString(12, watch.status.name)
            ps.setInt(13, watch.version)
            ps.setInstant(14, watch.updatedAt)
            ps.setObject(15, UUID.fromString(watch.id))
            ps.executeUpdate()
        }
    }

    private fun ResultSet.toWatch(): Watch = Watch(
        id = getObject("id", UUID::class.java).toString(),
        workspaceId = getObject("workspace_id", UUID::class.java).toString(),
        createdBy = getObject("created_by", UUID::class.java).toString(),
        name = getString("name"),
        endpointIds = decodeList(getString("endpoint_ids")).toSet(),
        eventTypes = decodeList(getString("event_types")).toSet(),
        entityIds = decodeList(getString("entity_ids")).toSet(),
        keywordsInclude = decodeList(getString("keywords_include")),
        keywordsExclude = decodeList(getString("keywords_exclude")),
        semantic = getObject("semantic") as String?,
        maxAgeHours = getInt("max_age_hours").let { if (wasNull()) null else it },
        minSignificance = getString("min_significance"),
        minConfidence = getString("min_confidence"),
        notificationMode = com.mezo.trace.domain.NotificationMode.valueOf(getString("notification_mode")),
        status = com.mezo.trace.domain.WatchStatus.valueOf(getString("status")),
        version = getInt("version"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )
}

class JdbcAlertRepository(private val sql: SqlConnections) : AlertRepository {

    override fun insertIfAbsent(alert: Alert): Alert? {
        val inserted = sql.current().prepareStatement(
            """
            INSERT INTO alerts (id, workspace_id, watch_id, event_id, relevance, significance, status, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (watch_id, event_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(alert.id))
            ps.setObject(2, UUID.fromString(alert.workspaceId))
            ps.setObject(3, UUID.fromString(alert.watchId))
            ps.setObject(4, UUID.fromString(alert.eventId))
            ps.setString(5, objJson(alert.relevance))
            ps.setString(6, alert.significance)
            ps.setString(7, alert.status.name)
            ps.setInstant(8, alert.createdAt)
            ps.executeUpdate() > 0
        }
        return if (inserted) alert else null
    }

    override fun listForWorkspace(workspaceId: String, limit: Int): List<Alert> =
        sql.current().prepareStatement(
            "SELECT * FROM alerts WHERE workspace_id = ? ORDER BY created_at DESC LIMIT ?",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(workspaceId))
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Alert>()
                while (rs.next()) {
                    out += Alert(
                        id = rs.getObject("id", UUID::class.java).toString(),
                        workspaceId = rs.getObject("workspace_id", UUID::class.java).toString(),
                        watchId = rs.getObject("watch_id", UUID::class.java).toString(),
                        eventId = rs.getObject("event_id", UUID::class.java).toString(),
                        relevance = emptyMap(),
                        significance = rs.getString("significance"),
                        status = com.mezo.trace.domain.AlertStatus.valueOf(rs.getString("status")),
                        createdAt = rs.instant("created_at"),
                    )
                }
                out
            }
        }

    override fun countForEvent(eventId: String): Int {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return 0
        return sql.current().prepareStatement("SELECT count(*) FROM alerts WHERE event_id = ?").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    private fun objJson(map: Map<String, String>): String =
        kotlinx.serialization.json.buildJsonObject {
            map.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }.toString()
}

internal fun doubleMapJson(map: Map<String, Double>): String =
        kotlinx.serialization.json.buildJsonObject {
            map.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }.toString()

internal fun decodeDoubleMap(json: String?): Map<String, Double> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        obj.entries.mapNotNull { (k, v) ->
            ((v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull())?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}

private val EVENT_MAPPER = com.mezo.trace.infra.persistence.EventRowMapper()

internal fun listJson(values: List<String>): String =
    kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.serializer<List<String>>(),
        values,
    )

internal fun decodeList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.serializer<List<String>>(),
            json,
        )
    }.getOrDefault(emptyList())
}
