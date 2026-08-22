package com.mezo.trace.application.usecase.auth

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.application.port.TxRunner

/**
 * Revokes the refresh-token family the presented token belongs to.
 * Idempotent: an unknown token still succeeds (Rule 4).
 */
class SignOut(
    private val refreshTokens: RefreshTokenRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val tokenIssuer: TokenIssuer,
    private val clock: ClockPort,
) {

    fun execute(refreshRaw: String?) {
        tx.run {
            val hash = refreshRaw?.takeIf { it.isNotBlank() }?.let { tokenIssuer.hash(it) }
            val record = hash?.let { refreshTokens.findByHash(it) }
            if (record != null) {
                refreshTokens.revokeFamily(record.familyId, clock.now())
            }
            audit.append(
                AuditEntry(
                    action = "USER_SIGNED_OUT",
                    actorType = ActorType.USER,
                    actorId = record?.userId?.value,
                    resourceType = "refresh_token_family",
                    resourceId = record?.familyId?.toString(),
                ),
            )
        }
    }
}
