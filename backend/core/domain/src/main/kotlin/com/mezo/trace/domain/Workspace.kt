package com.mezo.trace.domain

import java.time.Instant

/**
 * The tenant boundary. All tenant-owned resources belong to a workspace.
 */
data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val createdBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
)
