package com.mezo.trace.application.usecase.entities

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.EntityRepository
import com.mezo.trace.application.port.TxRunner

/**
 * Merges a duplicate entity into its cluster survivor (Phase 6 audit fix A1).
 *
 * False merges must be recoverable: the loser row is never deleted, and the
 * `ENTITY_MERGED` audit entry records every identifier/alias that moved so an
 * operator can reconstruct the pre-merge state. Re-running the same merge is
 * a no-op (Rule 4); merging into a *different* survivor after a prior merge
 * is rejected to prevent silent re-parenting.
 */
class MergeEntities(
    private val entities: EntityRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
) {

    fun execute(actorId: String, loserId: String, survivorId: String) {
        tx.run {
            if (loserId == survivorId) {
                throw AppError.ValidationFailed("target_id", "cannot merge an entity into itself")
            }
            val loser = entities.findById(loserId) ?: throw AppError.NotFound("entity")
            entities.findById(survivorId) ?: throw AppError.NotFound("target entity")

            if (loser.type != entities.findById(survivorId)!!.type) {
                throw AppError.ValidationFailed("target_id", "cannot merge across entity types")
            }

            loser.mergedIntoEntityId?.let { existing ->
                if (existing == survivorId) return@run // idempotent replay
                throw AppError.Conflict(
                    "entity already merged into $existing; unmerge first",
                )
            }

            // Capture what moves so the merge is reversible from the audit log.
            val movedIdentifiers = entities.identifiersOf(loser.id)
            val movedAliases = entities.aliasesOf(loser.id)

            entities.mergeInto(loser.id, survivorId)

            audit.append(
                AuditEntry(
                    action = "ENTITY_MERGED",
                    actorType = ActorType.USER,
                    actorId = actorId,
                    resourceType = "entity",
                    resourceId = loser.id,
                    metadata = mapOf(
                        "survivor" to survivorId,
                        "moved_identifiers" to movedIdentifiers.joinToString(";"),
                        "moved_aliases" to movedAliases.joinToString(";"),
                    ),
                ),
            )
        }
    }
}
