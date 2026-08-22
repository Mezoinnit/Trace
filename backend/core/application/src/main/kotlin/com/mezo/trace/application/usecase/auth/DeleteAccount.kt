package com.mezo.trace.application.usecase.auth

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.UserRepository
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.domain.UserId

/**
 * Account deletion (plan §26).
 *
 * Deletes the user, their sole-member workspaces, and revokes all sessions.
 * Deletion is refused while the user still belongs to a multi-member
 * workspace so shared tenant data is never silently lost (Rule 5).
 */
class DeleteAccount(
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
) {

    fun execute(userId: UserId) {
        tx.run {
            users.findById(userId) ?: throw AppError.NotFound("user")

            if (workspaces.hasSharedMembership(userId)) {
                throw AppError.Conflict("leave shared workspaces before deleting account")
            }

            workspaces.findSoleMemberWorkspaceIds(userId).forEach { workspaceId ->
                workspaces.delete(workspaceId)
                audit.append(
                    AuditEntry(
                        action = "WORKSPACE_DELETED",
                        actorType = ActorType.USER,
                        actorId = userId.value,
                        workspaceId = workspaceId,
                        resourceType = "workspace",
                        resourceId = workspaceId.value,
                        metadata = mapOf("reason" to "account_deleted"),
                    ),
                )
            }

            refreshTokens.revokeAllForUser(userId, clock.now())
            users.delete(userId)

            audit.append(
                AuditEntry(
                    action = "ACCOUNT_DELETED",
                    actorType = ActorType.USER,
                    actorId = userId.value,
                    resourceType = "user",
                    resourceId = userId.value,
                ),
            )
        }
    }
}
