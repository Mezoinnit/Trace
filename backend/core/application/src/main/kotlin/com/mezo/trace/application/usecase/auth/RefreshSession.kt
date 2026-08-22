package com.mezo.trace.application.usecase.auth

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.model.RefreshTokenRecord
import com.mezo.trace.application.model.TokenStatus
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.UserRepository
import java.time.Duration
import java.util.UUID

/**
 * Refresh-token rotation with family-based reuse detection.
 *
 * - A valid ACTIVE token is marked REPLACED and a sibling is issued in the
 *   same family.
 * - Presenting an already REPLACED/REVOKED token means the family is
 *   compromised (or replayed): the whole family is revoked and the event is
 *   audited (ADR-015).
 */
class RefreshSession(
    private val refreshTokens: RefreshTokenRepository,
    private val users: UserRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val tokenIssuer: com.mezo.trace.application.port.TokenIssuer,
    private val clock: ClockPort,
    private val refreshTtl: Duration,
) {

    data class Refreshed(
        val user: com.mezo.trace.domain.User,
        val session: AuthSessionIssuer.Session,
    )

    fun execute(refreshRaw: String): Refreshed {
        return tx.run {
            if (refreshRaw.isBlank()) throw AppError.Unauthenticated("invalid refresh token")
            val record = refreshTokens.findByHash(tokenIssuer.hash(refreshRaw))
                ?: throw AppError.Unauthenticated("invalid refresh token")

            when {
                record.status != TokenStatus.ACTIVE -> {
                    refreshTokens.revokeFamily(record.familyId, clock.now())
                    audit.append(
                        AuditEntry(
                            action = "REFRESH_TOKEN_REUSE",
                            actorType = ActorType.USER,
                            actorId = record.userId.value,
                            resourceType = "refresh_token_family",
                            resourceId = record.familyId.toString(),
                        ),
                    )
                    throw AppError.Unauthenticated("invalid refresh token")
                }

                !record.expiresAt.isAfter(clock.now()) ->
                    throw AppError.Unauthenticated("invalid refresh token")

                else -> rotate(record)
            }
        }
    }

    private fun rotate(record: RefreshTokenRecord): Refreshed {
        val user = users.findById(record.userId)
            ?: throw AppError.Unauthenticated("invalid refresh token")

        refreshTokens.markReplaced(record.id, clock.now())

        val rawRefresh = tokenIssuer.generateRefreshToken()
        val now = clock.now()
        refreshTokens.insert(
            RefreshTokenRecord(
                id = UUID.randomUUID(),
                userId = user.id,
                tokenHash = tokenIssuer.hash(rawRefresh),
                familyId = record.familyId,
                status = TokenStatus.ACTIVE,
                expiresAt = now.plus(refreshTtl),
                createdAt = now,
            ),
        )
        return Refreshed(user, AuthSessionIssuer.Session(tokenIssuer.issueAccessToken(user.id), rawRefresh))
    }
}
