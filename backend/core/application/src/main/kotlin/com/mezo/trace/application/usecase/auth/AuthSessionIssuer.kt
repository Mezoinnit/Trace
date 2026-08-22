package com.mezo.trace.application.usecase.auth

import com.mezo.trace.application.model.RefreshTokenRecord
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.IssuedAccessToken
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.domain.User
import java.time.Duration
import java.util.UUID

/**
 * Issues an access token and persists a new refresh-token family.
 *
 * Shared by registration and sign-in so session semantics stay identical.
 */
class AuthSessionIssuer(
    private val refreshTokens: RefreshTokenRepository,
    private val tokenIssuer: TokenIssuer,
    private val clock: ClockPort,
    private val refreshTtl: Duration,
) {

    data class Session(
        val accessToken: IssuedAccessToken,
        val refreshToken: String,
    )

    fun issue(user: User): Session {
        val access = tokenIssuer.issueAccessToken(user.id)
        val rawRefresh = tokenIssuer.generateRefreshToken()
        val now = clock.now()
        refreshTokens.insert(
            RefreshTokenRecord(
                id = UUID.randomUUID(),
                userId = user.id,
                tokenHash = tokenIssuer.hash(rawRefresh),
                familyId = UUID.randomUUID(),
                status = com.mezo.trace.application.model.TokenStatus.ACTIVE,
                expiresAt = now.plus(refreshTtl),
                createdAt = now,
            ),
        )
        return Session(access, rawRefresh)
    }
}
