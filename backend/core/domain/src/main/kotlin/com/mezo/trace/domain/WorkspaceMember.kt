package com.mezo.trace.domain

import java.time.Instant

/**
 * Membership of a user in a workspace with their role.
 */
data class WorkspaceMember(
    val workspaceId: WorkspaceId,
    val userId: UserId,
    val role: Role,
    val createdAt: Instant,
)
