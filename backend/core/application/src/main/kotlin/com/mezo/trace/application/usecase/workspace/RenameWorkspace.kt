package com.mezo.trace.application.usecase.workspace

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId

class RenameWorkspace(
    private val workspaces: WorkspaceRepository,
    private val requireMembership: RequireMembership,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
) {

    fun execute(userId: UserId, workspaceId: WorkspaceId, nameRaw: String) {
        tx.run {
            requireMembership.requireOwner(userId, workspaceId)
            val name = CreateWorkspace.validateName(nameRaw)

            val existing = workspaces.findById(workspaceId)
                ?: throw com.mezo.trace.application.AppError.NotFound("workspace")
            workspaces.rename(workspaceId, name, clock.now())

            audit.append(
                AuditEntry(
                    action = "WORKSPACE_UPDATED",
                    actorType = ActorType.USER,
                    actorId = userId.value,
                    workspaceId = workspaceId,
                    resourceType = "workspace",
                    resourceId = workspaceId.value,
                    metadata = mapOf("name_from" to existing.name, "name_to" to name),
                ),
            )
        }
    }
}
