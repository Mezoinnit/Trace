package com.mezo.trace.application.usecase.workspace

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.ValidationException
import com.mezo.trace.domain.Workspace
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId
import com.mezo.trace.domain.WorkspaceMember

class CreateWorkspace(
    private val workspaces: WorkspaceRepository,
    private val memberships: WorkspaceMembershipRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    fun execute(userId: UserId, nameRaw: String): Workspace {
        return tx.run {
            val name = validateName(nameRaw)
            val now = clock.now()
            val workspaceId = WorkspaceId(idGenerator())

            workspaces.insert(
                Workspace(workspaceId, name, userId, now, now),
            )
            memberships.insert(WorkspaceMember(workspaceId, userId, Role.OWNER, now))

            audit.append(
                AuditEntry(
                    action = "WORKSPACE_CREATED",
                    actorType = ActorType.USER,
                    actorId = userId.value,
                    workspaceId = workspaceId,
                    resourceType = "workspace",
                    resourceId = workspaceId.value,
                    metadata = mapOf("name" to name),
                ),
            )
            workspaces.findById(workspaceId)!!
        }
    }

    companion object {
        fun validateName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty()) throw ValidationException("name", "name is required")
            if (name.length > 200) {
                throw ValidationException("name", "name must be at most 200 characters")
            }
            return name
        }
    }
}
