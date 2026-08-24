package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AlertRepository
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WatchRepository
import com.mezo.trace.domain.Event
import java.util.UUID

/**
 * Matches an event against active Watches bound to its endpoint and creates
 * exactly-one alerts (ADR-012). Matching is deterministic: endpoint
 * containment + event-type filter; empty event_types means "all types".
 */
class MatchWatchesForEvent(
    private val watches: WatchRepository,
    private val alerts: AlertRepository,
    private val audit: AuditLogRepository,
    private val entities: com.mezo.trace.application.port.EntityRepository?,
    private val aiGateway: com.mezo.trace.application.port.AiGateway? = null,
    private val tx: TxRunner,
) {

    data class MatchSummary(
        val matchedWatches: Int,
        val alertsCreated: Int,
        /** Workspace ids that received a new alert. */
        val workspacesNotified: Set<String>,
    )

    fun execute(event: Event): MatchSummary {
        val endpointId = event.externalRef[DeriveEventsFromEvidence.REF_ENDPOINT_ID]
            ?: return MatchSummary(0, 0, emptySet())

        return tx.run {
            // Entity links for entity-constrained watches (Phase 6 graph).
            val linkedEntityIds = entities
                ?.listEventEntities(event.id)
                ?.map { it.id }
                ?.toSet()
                ?: emptySet()

            val eventText = event.title + "\n" + event.summary.orEmpty()
            val candidates = watches
                .findActiveForEndpoint(endpointId)
                .filter { watch ->
                    val base = com.mezo.trace.application.usecase.watches.WatchEvaluator.evaluate(
                        watch, event, linkedEntityIds,
                    )
                    if (!base.matched) return@filter false

                    // Phase 10: the stored semantic description now participates
                    // via the AI gateway; without a provider it degrades to pass
                    // and is noted in the alert's relevance map.
                    val semantic = watch.semantic?.trim()
                    if (semantic.isNullOrEmpty()) {
                        true
                    } else {
                        val score = aiGateway?.semanticRelevanceOrNull(semantic, eventText)
                        score == null || score >= SEMANTIC_THRESHOLD
                    }
                }

            var created = 0
            val workspaces = mutableSetOf<String>()
            candidates.forEach { watch ->
                val inserted = alerts.insertIfAbsent(
                    com.mezo.trace.domain.Alert(
                        id = UUID.randomUUID().toString(),
                        workspaceId = watch.workspaceId,
                        watchId = watch.id,
                        eventId = event.id,
                        relevance = mapOf(
                            "matched_endpoint" to endpointId,
                            "matched_event_type" to event.type,
                            "semantic_evaluated" to (aiGateway?.available == true).toString(),
                        ),
                        significance = event.significance,
                        status = com.mezo.trace.domain.AlertStatus.PENDING,
                        createdAt = java.time.Instant.now(),
                    ),
                )
                if (inserted != null) {
                    created++
                    workspaces += watch.workspaceId
                }
            }

            if (created > 0) {
                audit.append(
                    AuditEntry(
                        action = "ALERTS_CREATED",
                        actorType = ActorType.JOB,
                        resourceType = "event",
                        resourceId = event.id,
                        metadata = mapOf("alerts" to created.toString(), "endpoint_id" to endpointId),
                    ),
                )
            }
            MatchSummary(candidates.size, created, workspaces)
        }
    }

    companion object {
        /** Minimum semantic relevance for a semantic-constrained watch. */
        const val SEMANTIC_THRESHOLD = 0.5
    }
}
