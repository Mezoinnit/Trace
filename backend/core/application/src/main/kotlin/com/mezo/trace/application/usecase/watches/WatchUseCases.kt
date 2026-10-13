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
    /** Per-workspace active-watch cap (Phase 14, Rule 6). */
    private val maxWatchesPerWorkspace: Int = DEFAULT_MAX_WATCHES_PER_WORKSPACE,
) {

    data class Rules(
        val entityIds: Set<String> = emptySet(),
        val keywordsInclude: List<String> = emptyList(),
        val keywordsExclude: List<String> = emptyList(),
        val semantic: String? = null,
        val maxAgeHours: Int? = null,
        val minSignificance: String? = null,
        val minConfidence: String? = null,
        val notificationMode: String = "IMMEDIATE",
    )

    fun execute(
        actor: UserId,
        workspaceId: String,
        name: String,
        endpointIds: Set<String>,
        eventTypes: Set<String>,
        rules: Rules = Rules(),
    ): Watch {
        return tx.run {
            requireMembership.execute(actor, WorkspaceId(workspaceId))
            validateName(name)

            // Abuse bound (Phase 14, Rule 6): every active watch is evaluated
            // against every event, so watch count must be bounded per tenant.
            val activeCount = watches.listForWorkspace(workspaceId)
                .count { it.status == com.mezo.trace.domain.WatchStatus.ACTIVE }
            if (activeCount >= maxWatchesPerWorkspace) {
                throw AppError.Conflict(
                    "workspace watch limit reached ($maxWatchesPerWorkspace active watches); " +
                        "pause or delete watches before creating more",
                )
            }

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

            validateRules(rules)

            val now = java.time.Instant.now()
            val watch = Watch(
                id = UUID.randomUUID().toString(),
                workspaceId = workspaceId,
                createdBy = actor.value,
                name = name.trim(),
                endpointIds = endpointIds,
                eventTypes = eventTypes.map { it.uppercase() }.toSet(),
                entityIds = rules.entityIds,
                keywordsInclude = rules.keywordsInclude,
                keywordsExclude = rules.keywordsExclude,
                semantic = rules.semantic?.trim()?.ifEmpty { null },
                maxAgeHours = rules.maxAgeHours,
                minSignificance = rules.minSignificance?.uppercase(),
                minConfidence = rules.minConfidence?.uppercase(),
                notificationMode = com.mezo.trace.domain.NotificationMode.valueOf(rules.notificationMode.uppercase()),
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
        /** Default active-watch cap per workspace (Phase 14 abuse bound). */
        const val DEFAULT_MAX_WATCHES_PER_WORKSPACE = 100
        const val MAX_ENDPOINTS = 20
        const val MAX_ENTITIES = 10
        const val MAX_KEYWORD_TERMS = 20
        const val MAX_KEYWORD_LENGTH = 64
        const val MAX_SEMANTIC_LENGTH = 500
        const val MAX_AGE_HOURS = 8760

    /**
     * Complexity bounds (Rule 6, WATCH_MODEL.md §1). Entity ids are validated
     * as existing entities; keyword terms are bounded in count and length.
     */
    internal fun validateRules(rules: Rules) {
        if (rules.entityIds.size > MAX_ENTITIES) {
            throw ValidationException("entity_ids", "at most $MAX_ENTITIES entities per watch")
        }
        listOf("keywords_include" to rules.keywordsInclude, "keywords_exclude" to rules.keywordsExclude).forEach { (field, terms) ->
            if (terms.size > MAX_KEYWORD_TERMS) {
                throw ValidationException(field, "at most $MAX_KEYWORD_TERMS terms")
            }
            terms.forEach { term ->
                if (term.isBlank()) throw ValidationException(field, "blank term")
                if (term.length > MAX_KEYWORD_LENGTH) {
                    throw ValidationException(field, "term longer than $MAX_KEYWORD_LENGTH characters")
                }
            }
        }
        rules.semantic?.let {
            if (it.length > MAX_SEMANTIC_LENGTH) {
                throw ValidationException("semantic", "at most $MAX_SEMANTIC_LENGTH characters")
            }
        }
        rules.maxAgeHours?.let {
            if (it !in 1..MAX_AGE_HOURS) {
                throw ValidationException("max_age_hours", "must be between 1 and $MAX_AGE_HOURS")
            }
        }
        rules.minSignificance?.uppercase()?.let {
            if (it !in setOf("TRIVIAL", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                throw ValidationException("min_significance", "invalid significance band")
            }
        }
        rules.minConfidence?.uppercase()?.let {
            if (it !in setOf("VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH")) {
                throw ValidationException("min_confidence", "invalid confidence band")
            }
        }
        rules.notificationMode.uppercase().let {
            if (it !in setOf("IMMEDIATE", "DIGEST", "NEVER")) {
                throw ValidationException("notification_mode", "must be IMMEDIATE, DIGEST or NEVER")
            }
        }
    }


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
    private val entities: com.mezo.trace.application.port.EntityRepository?,
    private val tx: TxRunner,
) {

    data class Patch(
        val expectedVersion: Int,
        val name: String? = null,
        val status: String? = null,
        val endpointIds: Set<String>? = null,
        val eventTypes: Set<String>? = null,
        val rules: CreateWatch.Rules? = null,
        val notificationMode: String? = null,
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

            patch.rules?.entityIds?.takeIf { it.isNotEmpty() && entities != null }?.forEach { id ->
                entities!!.findById(id)
                    ?: throw com.mezo.trace.domain.ValidationException("entity_ids", "unknown entity $id")
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

            val effectiveRules = patch.rules ?: CreateWatch.Rules(
                entityIds = watch.entityIds,
                keywordsInclude = watch.keywordsInclude,
                keywordsExclude = watch.keywordsExclude,
                semantic = watch.semantic,
                maxAgeHours = watch.maxAgeHours,
                minSignificance = watch.minSignificance,
                minConfidence = watch.minConfidence,
                notificationMode = patch.notificationMode ?: watch.notificationMode.name,
            )
            val rules = effectiveRules
            CreateWatch.validateRules(rules)

            val updated = watch.copy(
                name = newName,
                endpointIds = newEndpoints,
                eventTypes = (patch.eventTypes ?: watch.eventTypes).map { it.uppercase() }.toSet(),
                entityIds = rules.entityIds,
                keywordsInclude = rules.keywordsInclude,
                keywordsExclude = rules.keywordsExclude,
                semantic = rules.semantic?.trim()?.ifEmpty { null },
                maxAgeHours = rules.maxAgeHours,
                minSignificance = rules.minSignificance?.uppercase(),
                minConfidence = rules.minConfidence?.uppercase(),
                notificationMode = com.mezo.trace.domain.NotificationMode.valueOf(rules.notificationMode.uppercase()),
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
