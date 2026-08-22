package com.mezo.trace.application.port

import com.mezo.trace.application.model.MemberInfo
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.WorkspaceMember
import com.mezo.trace.domain.UserId

interface WorkspaceMembershipRepository {
    fun insert(member: WorkspaceMember)
    fun findRole(workspaceId: WorkspaceId, userId: UserId): Role?
    fun listMembers(workspaceId: WorkspaceId): List<WorkspaceMember>
    fun listMemberInfo(workspaceId: WorkspaceId): List<MemberInfo>
}
