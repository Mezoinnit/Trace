package com.mezo.trace.infra.persistence

import com.mezo.trace.application.model.RefreshTokenRecord
import com.mezo.trace.application.model.TokenStatus
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.domain.UserId
import java.time.Instant
import java.util.UUID

class JdbcRefreshTokenRepository(private val sql: SqlConnections) : RefreshTokenRepository {

    override fun insert(record: RefreshTokenRecord) {
        sql.current().prepareStatement(
            """
            INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, status, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, record.id)
            ps.setObject(2, UUID.fromString(record.userId.value))
            ps.setString(3, record.tokenHash)
            ps.setObject(4, record.familyId)
            ps.setString(5, record.status.name)
            ps.setInstant(6, record.expiresAt)
            ps.setInstant(7, record.createdAt)
            ps.executeUpdate()
        }
    }

    override fun findByHash(tokenHash: String): RefreshTokenRecord? {
        sql.current().prepareStatement("SELECT * FROM refresh_tokens WHERE token_hash = ?").use { ps ->
            ps.setString(1, tokenHash)
            ps.executeQuery().use { rs -> if (rs.next()) return rs.toRecord() }
        }
        return null
    }

    override fun markReplaced(id: UUID, now: Instant) {
        sql.current().prepareStatement(
            """
            UPDATE refresh_tokens
            SET status = 'REPLACED', rotated_at = ?
            WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, now)
            ps.setObject(2, id)
            ps.executeUpdate()
        }
    }

    override fun revokeFamily(familyId: UUID, now: Instant) {
        revoke("family_id = ?", familyId, now)
    }

    override fun revokeAllForUser(userId: UserId, now: Instant) {
        revoke("user_id = ?", UUID.fromString(userId.value), now)
    }

    private fun revoke(whereClause: String, param: UUID, now: Instant) {
        sql.current().prepareStatement(
            """
            UPDATE refresh_tokens
            SET status = 'REVOKED', revoked_at = ?
            WHERE $whereClause AND status IN ('ACTIVE', 'REPLACED')
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, now)
            ps.setObject(2, param)
            ps.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toRecord(): RefreshTokenRecord = RefreshTokenRecord(
        id = getObject("id", UUID::class.java),
        userId = UserId(getObject("user_id", UUID::class.java).toString()),
        tokenHash = getString("token_hash"),
        familyId = getObject("family_id", UUID::class.java),
        status = TokenStatus.valueOf(getString("status")),
        expiresAt = instant("expires_at"),
        createdAt = instant("created_at"),
    )
}
