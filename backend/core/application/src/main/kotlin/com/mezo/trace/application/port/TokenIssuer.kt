package com.mezo.trace.application.port

import com.mezo.trace.domain.UserId
import java.time.Instant

/**
 * Issued short-lived access token (JWT).
 */
data class IssuedAccessToken(
    val token: String,
    val expiresAt: Instant,
)

/**
 * Token issuance port. Implemented in infrastructure/security.
 *
 * The domain never depends on a JWT library; swapping to an external OIDC
 * provider means replacing this port only (ADR-015).
 */
interface TokenIssuer {
    fun issueAccessToken(userId: UserId): IssuedAccessToken

    /** Opaque high-entropy refresh token material (raw form, returned once). */
    fun generateRefreshToken(): String

    /** Deterministic hash used for server-side refresh token storage. */
    fun hash(token: String): String

    /** Validates an access token signature/expiry and returns its subject. */
    fun validateAccessToken(token: String): UserId?
}
