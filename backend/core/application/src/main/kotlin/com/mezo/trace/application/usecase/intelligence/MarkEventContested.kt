package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.EventRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.EventStatus
import java.util.UUID

/**
 * Contradiction handling (plan §14, Rule 5): conflicting evidence is
 * **preserved and surfaced**, never silently merged. Marking evidence as
 * contradicting flips the event to CONTESTED; the UI must expose the
 * disagreement. Automated detection joins in Phase 10 — this use case is the
 * deterministic state transition every detector funnels into.
 */
class MarkEventContested(
    private val events: EventRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
) {

    fun execute(
        actorId: String?,
        eventId: String,
        contradictingEvidenceId: String,
        reason: String,
    ) {
        tx.run {
            val event = events.findById(eventId)
                ?: throw com.mezo.trace.application.AppError.NotFound("event")

            // Idempotent: re-marking the same evidence is a no-op.
            if (event.status != EventStatus.CONTESTED) {
                events.updateStatus(event.id, EventStatus.CONTESTED)
            }
            events.markEvidenceRole(contradictingEvidenceId, eventId, ROLE_CONTRADICTING)

            audit.append(
                AuditEntry(
                    action = "EVENT_CONTESTED",
                    actorType = if (actorId == null) ActorType.JOB else ActorType.USER,
                    actorId = actorId,
                    resourceType = "event",
                    resourceId = event.id,
                    metadata = mapOf(
                        "contradicting_evidence" to contradictingEvidenceId,
                        "reason" to reason.take(200),
                    ),
                ),
            )
        }
    }

    companion object {
        const val ROLE_CONTRADICTING = "CONTRADICTING"
        val ID_GENERATOR: () -> String = { UUID.randomUUID().toString() }
    }
}
