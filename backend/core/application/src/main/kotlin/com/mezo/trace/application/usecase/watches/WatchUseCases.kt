package com.mezo.trace.application.usecase.watches

import java.util.UUID

import com.mezo.trace.application.AppError
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WatchRepository
import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.domain.ValidationException
import com.mezo.trace.domain.Watch
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId

/**
 * Persistent monitoring rules (Phase 4 slice). The server verifies workspace
 * membership from the authenticated identity — a client-supplied workspace_id
 * is a claim to verify, never a trust anchor (ADR-010). A watch must name at
 * least one exact endpoint; unconstrained "watch everything" is rejected.
 */
class CreateWatch(
    private val watches: WatchRepository,
    private val endpoints: com.mezo.trace.application.port.SourceEndpointRepository,
    private val requireMembership: RequireMembership,
    private val tx: TxRunner,
) {

    fun execute(
        actor: UserId,
        workspaceId: String,
        name: String,
        endpointIds: Set<String>,
        eventTypes: Set<String>,
    ): Watch {
        return tx.run {
            requireMembership.execute(actor, WorkspaceId(workspaceId))
            validateName(name)
            if (endpointIds.isEmpty()) {
                throw ValidationException("endpoint_ids", "at least one endpoint is required")
            }
            if (endpointIds.size > MAX_ENDPOINTS) {
                throw ValidationException("endpoint_ids", "at most $MAX_ENDPOINTS endpoints per watch")
            }
            endpointIds.forEach { id ->
                endpoints.findById(id)
                    ?: throw ValidationException("endpoint_ids", "unknown endpoint $id")
            }

            val now = java.time.Instant.now()
            val watch = Watch(
                id = UUID.randomUUID().toString(),
                workspaceId = workspaceId,
                createdBy = actor.value,
                name = name.trim(),
                endpointIds = endpointIds,
                eventTypes = eventTypes.map { it.uppercase() }.toSet(),
                status = com.mezo.trace.domain.WatchStatus.ACTIVE,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
            watches.insert(watch)
            watch
        }
    }

    companion object {
        const val MAX_ENDPOINTS = 20

        fun validateName(raw: String) {
            val name = raw.trim()
            when {
                name.isEmpty() -> throw ValidationException("name", "name is required")
                name.length > 200 -> throw ValidationException("name", "name must be at most 200 characters")
            }
        }
    }
}

class ListWatches(
    private val watches: WatchRepository,
    private val requireMembership: RequireMembership,
    private val tx: TxRunner,
) {

    fun execute(actor: UserId, workspaceId: String): List<Watch> {
        return tx.run {
            requireMembership.execute(actor, WorkspaceId(workspaceId))
            watches.listForWorkspace(workspaceId)
        }
    }
}

class DeleteWatch(
    private val watches: WatchRepository,
    private val requireMembership: RequireMembership,
    private val tx: TxRunner,
) {

    fun execute(actor: UserId, watchId: String) {
        tx.run {
            val watch = watches.findById(watchId) ?: throw AppError.NotFound("watch")
            requireMembership.execute(actor, WorkspaceId(watch.workspaceId))
            watches.setStatus(watch.id, com.mezo.trace.domain.WatchStatus.DELETED.name, java.time.Instant.now())
        }
    }
}
