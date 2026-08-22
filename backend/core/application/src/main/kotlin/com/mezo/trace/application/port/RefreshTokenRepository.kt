package com.mezo.trace.application.port

import com.mezo.trace.application.model.RefreshTokenRecord
import com.mezo.trace.domain.UserId
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepository {
    fun insert(record: RefreshTokenRecord)

    /** Finds a token by hash regardless of status (reuse detection needs this). */
    fun findByHash(tokenHash: String): RefreshTokenRecord?

    fun markReplaced(id: UUID, now: Instant)
    fun revokeFamily(familyId: UUID, now: Instant)
    fun revokeAllForUser(userId: UserId, now: Instant)
}
