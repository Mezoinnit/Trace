package com.mezo.trace.application.model

import com.mezo.trace.domain.WorkspaceId

enum class ActorType { USER, SYSTEM, JOB }

/**
 * Audit record for important state changes (OBSERVABILITY.md §5).
 */
data class AuditEntry(
    val action: String,
    val actorType: ActorType = ActorType.SYSTEM,
    val actorId: String? = null,
    val workspaceId: WorkspaceId? = null,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
