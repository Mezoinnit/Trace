package com.mezo.trace.application.usecase.workspace

import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.domain.Workspace
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId

class GetWorkspace(
    private val workspaces: WorkspaceRepository,
    private val requireMembership: RequireMembership,
    private val tx: TxRunner,
) {

    fun execute(userId: UserId, workspaceId: WorkspaceId): Workspace {
        return tx.run {
            requireMembership.execute(userId, workspaceId)
            workspaces.findById(workspaceId) ?: throw com.mezo.trace.application.AppError.NotFound("workspace")
        }
    }
}
