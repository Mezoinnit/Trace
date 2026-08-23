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
    private val audit: com.mezo.trace.application.port.AuditLogRepository,
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

            // §43: watch lifecycle changes create audit records.
            audit.append(
                com.mezo.trace.application.model.AuditEntry(
                    action = "WATCH_CREATED",
                    actorType = com.mezo.trace.application.model.ActorType.USER,
                    actorId = actor.value,
                    workspaceId = com.mezo.trace.domain.WorkspaceId(workspaceId),
                    resourceType = "watch",
                    resourceId = watch.id,
                    metadata = mapOf("name" to watch.name),
                ),
            )
            watch
        }
    }

    companion object {
        const val MAX_ENDPOINTS = 20

        fun validateName(raw: String): String {
            val name = raw.trim()
            when {
                name.isEmpty() -> throw ValidationException("name", "name is required")
                name.length > 200 -> throw ValidationException("name", "name must be at most 200 characters")
            }
            return name
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
    private val audit: com.mezo.trace.application.port.AuditLogRepository,
    private val tx: TxRunner,
) {

    fun execute(actor: UserId, watchId: String) {
        tx.run {
            val watch = watches.findById(watchId) ?: throw AppError.NotFound("watch")
            requireMembership.execute(actor, WorkspaceId(watch.workspaceId))
            watches.setStatus(watch.id, com.mezo.trace.domain.WatchStatus.DELETED.name, java.time.Instant.now())

            audit.append(
                com.mezo.trace.application.model.AuditEntry(
                    action = "WATCH_DELETED",
                    actorType = com.mezo.trace.application.model.ActorType.USER,
                    actorId = actor.value,
                    workspaceId = WorkspaceId(watch.workspaceId),
                    resourceType = "watch",
                    resourceId = watch.id,
                ),
            )
        }
    }
}

/**
 * Partial watch update with optimistic locking (plan §24): the client echoes
 * the version it read; a mismatch is a 409, never a silent overwrite.
 */
class UpdateWatch(
    private val watches: WatchRepository,
    private val endpoints: com.mezo.trace.application.port.SourceEndpointRepository,
    private val requireMembership: RequireMembership,
    private val audit: com.mezo.trace.application.port.AuditLogRepository,
    private val tx: TxRunner,
) {

    data class Patch(
        val expectedVersion: Int,
        val name: String? = null,
        val status: String? = null,
        val endpointIds: Set<String>? = null,
        val eventTypes: Set<String>? = null,
    )

    fun execute(actor: UserId, watchId: String, patch: Patch): Watch {
        return tx.run {
            val watch = watches.findById(watchId) ?: throw AppError.NotFound("watch")
            requireMembership.execute(actor, WorkspaceId(watch.workspaceId))

            if (watch.version != patch.expectedVersion) {
                throw AppError.Conflict(
                    "version mismatch: expected ${patch.expectedVersion}, current ${watch.version}",
                )
            }

            val newName = patch.name?.let { CreateWatch.validateName(it) } ?: watch.name
            val newEndpoints = patch.endpointIds ?: watch.endpointIds
            patch.endpointIds?.let { ids ->
                if (ids.isEmpty()) {
                    throw com.mezo.trace.domain.ValidationException(
                        "endpoint_ids",
                        "at least one endpoint is required",
                    )
                }
                if (ids.size > CreateWatch.MAX_ENDPOINTS) {
                    throw com.mezo.trace.domain.ValidationException(
                        "endpoint_ids",
                        "at most ${CreateWatch.MAX_ENDPOINTS} endpoints per watch",
                    )
                }
                ids.forEach { id ->
                    endpoints.findById(id)
                        ?: throw com.mezo.trace.domain.ValidationException("endpoint_ids", "unknown endpoint $id")
                }
            }
            val newStatus = patch.status
                ?.let { runCatching { com.mezo.trace.domain.WatchStatus.valueOf(it.uppercase()) }.getOrElse {
                    throw com.mezo.trace.domain.ValidationException("status", "must be ACTIVE or PAUSED")
                } }
                ?: watch.status

            val updated = watch.copy(
                name = newName,
                endpointIds = newEndpoints,
                eventTypes = (patch.eventTypes ?: watch.eventTypes).map { it.uppercase() }.toSet(),
                status = newStatus,
                version = watch.version + 1,
                updatedAt = java.time.Instant.now(),
            )
            watches.update(updated)

            audit.append(
                com.mezo.trace.application.model.AuditEntry(
                    action = "WATCH_UPDATED",
                    actorType = com.mezo.trace.application.model.ActorType.USER,
                    actorId = actor.value,
                    workspaceId = WorkspaceId(watch.workspaceId),
                    resourceType = "watch",
                    resourceId = watch.id,
                    metadata = mapOf("version" to updated.version.toString()),
                ),
            )
            updated
        }
    }
}
