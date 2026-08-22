package com.mezo.trace.application.usecase.tenancy

import com.mezo.trace.application.AppError
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId

/**
 * Resolves the caller's role in a workspace. Non-members get NotFound —
 * never Forbidden — so cross-tenant probing cannot enumerate resources
 * (API.md, ADR-010).
 */
class RequireMembership(
    private val memberships: WorkspaceMembershipRepository,
    private val tx: TxRunner,
) {

    fun execute(userId: UserId, workspaceId: WorkspaceId): Role {
        return tx.run {
            memberships.findRole(workspaceId, userId)
                ?: throw AppError.NotFound("workspace")
        }
    }

    fun requireOwner(userId: UserId, workspaceId: WorkspaceId): Role {
        val role = execute(userId, workspaceId)
        if (role != Role.OWNER) throw AppError.Forbidden("owner role required")
        return role
    }
}
