package com.mezo.trace.application.port

import com.mezo.trace.domain.Role
import com.mezo.trace.domain.Workspace
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId
import java.time.Instant

/**
 * Workspace with the caller's role, for listings.
 */
data class WorkspaceWithRole(
    val workspace: Workspace,
    val role: Role,
)

interface WorkspaceRepository {
    fun insert(workspace: Workspace)
    fun findById(id: WorkspaceId): Workspace?
    fun listForUser(userId: UserId): List<WorkspaceWithRole>
    fun rename(id: WorkspaceId, name: String, now: Instant)
    fun delete(id: WorkspaceId)

    /** Workspaces where the given user is the only member. */
    fun findSoleMemberWorkspaceIds(userId: UserId): List<WorkspaceId>

    /** True when the user belongs to at least one multi-member workspace. */
    fun hasSharedMembership(userId: UserId): Boolean
}
