package com.mezo.trace.application.model

import com.mezo.trace.domain.UserId
import java.time.Instant
import java.util.UUID

enum class TokenStatus { ACTIVE, REPLACED, REVOKED }

/**
 * Server-side refresh token state. Only hashes are persisted; the raw token
 * is shown to the client once at issuance.
 */
data class RefreshTokenRecord(
    val id: UUID,
    val userId: UserId,
    val tokenHash: String,
    val familyId: UUID,
    val status: TokenStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
)
