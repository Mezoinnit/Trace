package com.mezo.trace.application.usecase.workspace

import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.application.port.WorkspaceWithRole
import com.mezo.trace.domain.UserId

class ListWorkspaces(
    private val workspaces: WorkspaceRepository,
    private val tx: TxRunner,
) {

    fun execute(userId: UserId): List<WorkspaceWithRole> {
        return tx.run { workspaces.listForUser(userId) }
    }
}
