package com.mezo.trace.application.usecase.workspace

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId

class DeleteWorkspace(
    private val workspaces: WorkspaceRepository,
    private val requireMembership: RequireMembership,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
) {

    fun execute(userId: UserId, workspaceId: WorkspaceId) {
        tx.run {
            requireMembership.requireOwner(userId, workspaceId)
            workspaces.delete(workspaceId)

            audit.append(
                AuditEntry(
                    action = "WORKSPACE_DELETED",
                    actorType = ActorType.USER,
                    actorId = userId.value,
                    workspaceId = workspaceId,
                    resourceType = "workspace",
                    resourceId = workspaceId.value,
                ),
            )
        }
    }
}
