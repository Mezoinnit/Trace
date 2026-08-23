package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.EvidenceRepository
import com.mezo.trace.domain.Evidence
import com.mezo.trace.domain.EvidenceVersion
import java.util.UUID

class JdbcEvidenceRepository(private val sql: SqlConnections) : EvidenceRepository {

    override fun findByExternalIdentity(endpointId: String, externalId: String): Evidence? {
        val endpointUuid = runCatching { UUID.fromString(endpointId) }.getOrNull() ?: return null
        return sql.current().prepareStatement(
            "SELECT * FROM evidence WHERE endpoint_id = ? AND external_id = ?",
        ).use { ps ->
            ps.setObject(1, endpointUuid)
            ps.setString(2, externalId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEvidence() else null }
        }
    }

    override fun insert(evidence: Evidence): Boolean =
        sql.current().prepareStatement(
            """
            INSERT INTO evidence (id, source_id, endpoint_id, external_id, canonical_url, title,
                                  author, published_at, discovered_at, content, content_hash,
                                  raw_payload_reference, metadata, language, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (endpoint_id, external_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(evidence.id))
            ps.setObject(2, UUID.fromString(evidence.sourceId))
            ps.setObject(3, UUID.fromString(evidence.endpointId))
            ps.setString(4, evidence.externalId)
            ps.setNullableString(5, evidence.canonicalUrl)
            ps.setNullableString(6, evidence.title)
            ps.setNullableString(7, evidence.author)
            val publishedAt = evidence.publishedAt
            if (publishedAt != null) ps.setInstant(8, publishedAt) else ps.setNull(8, java.sql.Types.TIMESTAMP)
            ps.setInstant(9, evidence.discoveredAt)
            ps.setNullableString(10, evidence.content)
            ps.setString(11, evidence.contentHash)
            ps.setNullableString(12, evidence.rawPayloadReference)
            ps.setString(
                13,
                kotlinx.serialization.json.buildJsonObject {
                    evidence.metadata.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                }.toString(),
            )
            ps.setNullableString(14, evidence.language)
            ps.setInstant(15, evidence.createdAt)
            ps.executeUpdate() > 0
        }

    override fun latestVersion(evidenceId: String): Int {
        val uuid = runCatching { UUID.fromString(evidenceId) }.getOrNull() ?: return 0
        return sql.current().prepareStatement(
            "SELECT coalesce(max(version), 0) FROM evidence_versions WHERE evidence_id = ?",
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    override fun hasVersionWithHash(evidenceId: String, contentHash: String): Boolean {
        val uuid = runCatching { UUID.fromString(evidenceId) }.getOrNull() ?: return false
        return sql.current().prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM evidence_versions WHERE evidence_id = ? AND content_hash = ?)",
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.setString(2, contentHash)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    override fun appendVersion(version: EvidenceVersion) {
        sql.current().prepareStatement(
            """
            INSERT INTO evidence_versions (id, evidence_id, version, content, content_hash, changed_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(version.id))
            ps.setObject(2, UUID.fromString(version.evidenceId))
            ps.setInt(3, version.version)
            ps.setNullableString(4, version.content)
            ps.setString(5, version.contentHash)
            ps.setString(6, version.changedBy)
            ps.setInstant(7, version.createdAt)
            ps.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toEvidence(): Evidence = Evidence(
        id = getObject("id", UUID::class.java).toString(),
        sourceId = getObject("source_id", UUID::class.java).toString(),
        endpointId = getObject("endpoint_id", UUID::class.java).toString(),
        externalId = getString("external_id"),
        canonicalUrl = getObject("canonical_url") as String?,
        title = getObject("title") as String?,
        author = getObject("author") as String?,
        publishedAt = getObject("published_at")?.let { instant("published_at") },
        discoveredAt = instant("discovered_at"),
        content = getObject("content") as String?,
        contentHash = getString("content_hash"),
        rawPayloadReference = getObject("raw_payload_reference") as String?,
        metadata = decodeMetadata(getString("metadata")),
        language = getObject("language") as String?,
        createdAt = instant("created_at"),
    )

    private fun decodeMetadata(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            obj.entries.associate { (k, v) -> k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        }.getOrDefault(emptyMap())
    }
}
