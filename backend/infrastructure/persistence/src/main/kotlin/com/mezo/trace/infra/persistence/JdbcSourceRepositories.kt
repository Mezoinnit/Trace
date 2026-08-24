package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceHealthRepository
import com.mezo.trace.application.port.SourceRepository
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.HealthState
import com.mezo.trace.domain.Provider
import com.mezo.trace.domain.Source
import com.mezo.trace.domain.SourceEndpoint
import com.mezo.trace.domain.SourceHealth
import com.mezo.trace.domain.SourceStatus
import com.mezo.trace.domain.TrustClassification
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class JdbcSourceRepository(private val sql: SqlConnections) : SourceRepository {

    override fun insert(source: Source): Boolean {
        sql.current().prepareStatement(
            """
            INSERT INTO sources (id, provider, source_type, canonical_identity, display_name,
                                 trust_classification, verification_state, capabilities, status,
                                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (provider, source_type, canonical_identity) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(source.id))
            ps.setString(2, source.provider.name)
            ps.setString(3, source.sourceType)
            ps.setString(4, source.canonicalIdentity)
            ps.setNullableString(5, source.displayName)
            ps.setString(6, source.trust.name)
            ps.setString(7, source.verificationState)
            ps.setString(8, CAPS.encode(source.capabilities.map { it.name }))
            ps.setString(9, source.status.name)
            ps.setInstant(10, source.createdAt)
            ps.setInstant(11, source.updatedAt)
            return ps.executeUpdate() > 0
        }
    }

    override fun findById(id: String): Source? = byUuid("SELECT * FROM sources WHERE id = ?", id)

    override fun findByCanonicalIdentity(provider: String, sourceType: String, canonicalIdentity: String): Source? =
        sql.current().prepareStatement(
            "SELECT * FROM sources WHERE provider = ? AND source_type = ? AND canonical_identity = ?",
        ).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, sourceType)
            ps.setString(3, canonicalIdentity)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSource() else null }
        }

    override fun listAll(): List<Source> =
        sql.current().prepareStatement("SELECT * FROM sources ORDER BY created_at").use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Source>()
                while (rs.next()) out += rs.toSource()
                out
            }
        }

    override fun setStatus(id: String, status: String, now: Instant) {
        sql.current().prepareStatement("UPDATE sources SET status = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, status)
            ps.setInstant(2, now)
            ps.setObject(3, UUID.fromString(id))
            ps.executeUpdate()
        }
    }

    private fun byUuid(query: String, id: String): Source? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return sql.current().prepareStatement(query).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSource() else null }
        }
    }

    private fun ResultSet.toSource(): Source = Source(
        id = getObject("id", UUID::class.java).toString(),
        provider = Provider.valueOf(getString("provider")),
        sourceType = getString("source_type"),
        canonicalIdentity = getString("canonical_identity"),
        displayName = getObject("display_name") as String?,
        trust = TrustClassification.valueOf(getString("trust_classification")),
        verificationState = getString("verification_state"),
        capabilities = CAPS.decode(getString("capabilities")).map { Capability.valueOf(it) }.toSet(),
        status = SourceStatus.valueOf(getString("status")),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )
}

class JdbcSourceEndpointRepository(private val sql: SqlConnections) : SourceEndpointRepository {

    override fun insert(endpoint: SourceEndpoint): Boolean {
        sql.current().prepareStatement(
            """
            INSERT INTO source_endpoints (id, source_id, endpoint_uri, display_url, endpoint_type,
                                          capabilities, adapter, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (endpoint_uri) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(endpoint.id))
            ps.setObject(2, UUID.fromString(endpoint.sourceId))
            ps.setString(3, endpoint.endpointUri)
            ps.setNullableString(4, endpoint.displayUrl)
            ps.setString(5, endpoint.endpointType)
            ps.setString(6, CAPS.encode(endpoint.capabilities.map { it.name }))
            ps.setString(7, endpoint.adapter)
            ps.setString(8, endpoint.status.name)
            ps.setInstant(9, endpoint.createdAt)
            ps.setInstant(10, endpoint.updatedAt)
            return ps.executeUpdate() > 0
        }
    }

    override fun findById(id: String): SourceEndpoint? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return sql.current().prepareStatement("SELECT * FROM source_endpoints WHERE id = ?").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEndpoint() else null }
        }
    }

    override fun findByUri(endpointUri: String): SourceEndpoint? =
        sql.current().prepareStatement("SELECT * FROM source_endpoints WHERE endpoint_uri = ?").use { ps ->
            ps.setString(1, endpointUri)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEndpoint() else null }
        }

    override fun listBySourceId(sourceId: String): List<SourceEndpoint> =
        sql.current().prepareStatement(
            "SELECT * FROM source_endpoints WHERE source_id = ? ORDER BY created_at",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(sourceId))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<SourceEndpoint>()
                while (rs.next()) out += rs.toEndpoint()
                out
            }
        }

    override fun setStatus(id: String, status: String, now: Instant) {
        sql.current().prepareStatement("UPDATE source_endpoints SET status = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, status)
            ps.setInstant(2, now)
            ps.setObject(3, UUID.fromString(id))
            ps.executeUpdate()
        }
    }

    private fun ResultSet.toEndpoint(): SourceEndpoint = SourceEndpoint(
        id = getObject("id", UUID::class.java).toString(),
        sourceId = getObject("source_id", UUID::class.java).toString(),
        endpointUri = getString("endpoint_uri"),
        displayUrl = getObject("display_url") as String?,
        endpointType = getString("endpoint_type"),
        capabilities = CAPS.decode(getString("capabilities")).map { Capability.valueOf(it) }.toSet(),
        adapter = getString("adapter"),
        status = SourceStatus.valueOf(getString("status")),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    override fun findDueEndpointIds(now: Instant, limit: Int): List<String> =
        sql.current().prepareStatement(
            """
            SELECT se.id
            FROM source_endpoints se
            LEFT JOIN source_health sh ON sh.endpoint_id = se.id
            WHERE se.status = 'ACTIVE'
              AND (sh.next_poll_at IS NULL OR sh.next_poll_at <= ?)
            ORDER BY COALESCE(sh.next_poll_at, se.created_at)
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, now)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getObject("id", UUID::class.java).toString()
                out
            }
        }
}

private fun pgIntervalToMinutes(value: Any): Int? = runCatching {
    val iv = value as org.postgresql.util.PGInterval
    iv.minutes + (iv.hours * 60) + (iv.days * 1440)
}.getOrNull()

class JdbcSourceHealthRepository(private val sql: SqlConnections) : SourceHealthRepository {

    override fun initialize(endpointId: String) {
        sql.current().prepareStatement(
            "INSERT INTO source_health (endpoint_id) VALUES (?) ON CONFLICT (endpoint_id) DO NOTHING",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(endpointId))
            ps.executeUpdate()
        }
    }

    override fun findByEndpointId(endpointId: String): SourceHealth? {
        val uuid = runCatching { UUID.fromString(endpointId) }.getOrNull() ?: return null
        return sql.current().prepareStatement("SELECT * FROM source_health WHERE endpoint_id = ?").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                SourceHealth(
                    endpointId = rs.getObject("endpoint_id", UUID::class.java).toString(),
                    state = HealthState.valueOf(rs.getString("state")),
                    lastSuccessAt = rs.getObject("last_success_at")?.let { rs.instant("last_success_at") },
                    lastFailureAt = rs.getObject("last_failure_at")?.let { rs.instant("last_failure_at") },
                    failureCount = rs.getLong("failure_count"),
                    consecutiveFailures = rs.getInt("consecutive_failures"),
                    averageLatencyMs = rs.getInt("average_latency_ms").let { if (rs.wasNull()) null else it },
                    lastItemAt = rs.getObject("last_item_at")?.let { rs.instant("last_item_at") },
                    lastChangeAt = rs.getObject("last_change_at")?.let { rs.instant("last_change_at") },
                    lastEtag = rs.getString("last_etag"),
                    currentIntervalMinutes = rs.getObject("current_interval")
                        ?.let { pgIntervalToMinutes(it) },
                    nextPollAt = rs.getObject("next_poll_at")?.let { rs.instant("next_poll_at") },
                )
            }
        }
    }

    override fun applySuccess(endpointId: String, at: Instant, latencyMs: Int, itemCount: Int) {
        sql.current().prepareStatement(
            """
            UPDATE source_health SET
                state = 'HEALTHY',
                last_success_at = ?,
                consecutive_failures = 0,
                average_latency_ms = CASE
                    WHEN average_latency_ms IS NULL THEN ?
                    ELSE (average_latency_ms + ?) / 2
                END,
                last_item_at = CASE WHEN ? > 0 THEN ? ELSE last_item_at END,
                last_change_at = CASE WHEN ? > 0 THEN ? ELSE last_change_at END,
                updated_at = now()
            WHERE endpoint_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, at)
            ps.setInt(2, latencyMs)
            ps.setInt(3, latencyMs)
            ps.setInt(4, itemCount)
            ps.setInstant(5, at)
            ps.setInt(6, itemCount)
            ps.setInstant(7, at)
            ps.setObject(8, UUID.fromString(endpointId))
            ps.executeUpdate()
        }
    }

    override fun applyFailure(endpointId: String, at: Instant, failureState: String) {
        sql.current().prepareStatement(
            """
            UPDATE source_health SET
                state = ?,
                last_failure_at = ?,
                failure_count = failure_count + 1,
                consecutive_failures = consecutive_failures + 1,
                updated_at = now()
            WHERE endpoint_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, failureState)
            ps.setInstant(2, at)
            ps.setObject(3, UUID.fromString(endpointId))
            ps.executeUpdate()
        }
    }

    override fun applyInterval(
        endpointId: String,
        interval: java.time.Duration,
        consecutiveFailures: Int,
        at: Instant,
    ) {
        sql.current().prepareStatement(
            """
            UPDATE source_health SET
                next_poll_at = ?,
                current_interval = ?::text::interval,
                consecutive_failures = ?,
                state = CASE WHEN ? > 0 AND state = 'HEALTHY' THEN 'DEGRADED' ELSE state END,
                updated_at = now()
            WHERE endpoint_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, at.plus(interval))
            ps.setString(2, "PT" + interval.toMinutes() + "M")
            ps.setInt(3, consecutiveFailures)
            ps.setInt(4, consecutiveFailures)
            ps.setObject(5, UUID.fromString(endpointId))
            ps.executeUpdate()
        }
    }

    override fun markStaleIfOverdue(olderThan: Instant, now: Instant): Int =
        sql.current().prepareStatement(
            """
            UPDATE source_health SET state = 'STALE', updated_at = now()
            WHERE state = 'HEALTHY'
              AND last_success_at IS NOT NULL
              AND last_success_at < ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, olderThan)
            ps.executeUpdate()
        }

    override fun storeEtag(endpointId: String, etag: String?) {
        sql.current().prepareStatement(
            "UPDATE source_health SET last_etag = ?, updated_at = now() WHERE endpoint_id = ?",
        ).use { ps ->
            ps.setNullableString(1, etag)
            ps.setObject(2, UUID.fromString(endpointId))
            ps.executeUpdate()
        }
    }
}

private object CAPS {
    private val json = kotlinx.serialization.json.Json
    private val serializer = kotlinx.serialization.serializer<List<String>>()

    fun encode(values: List<String>): String = json.encodeToString(serializer, values)
    fun decode(value: String): List<String> = runCatching {
        json.decodeFromString(serializer, value)
    }.getOrDefault(emptyList())
}
