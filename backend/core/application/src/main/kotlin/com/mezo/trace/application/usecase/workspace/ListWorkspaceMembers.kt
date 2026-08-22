package com.mezo.trace.application.usecase.workspace

import com.mezo.trace.application.model.MemberInfo
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId

class ListWorkspaceMembers(
    private val memberships: WorkspaceMembershipRepository,
    private val requireMembership: RequireMembership,
    private val tx: TxRunner,
) {

    fun execute(userId: UserId, workspaceId: WorkspaceId): List<MemberInfo> {
        return tx.run {
            requireMembership.execute(userId, workspaceId)
            memberships.listMemberInfo(workspaceId)
        }
    }
}
