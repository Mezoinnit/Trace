package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.application.port.EventRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.usecase.evidence.IngestEvidence
import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import com.mezo.trace.domain.SourceEndpoint
import java.util.UUID

/**
 * Phase 4 vertical slice: deterministic SOFTWARE_RELEASE events from GitHub
 * release evidence (plan Phase 4). No AI involved — structured provider data.
 *
 * Replay safety: the event's external_ref (provider + external_id) is a
 * unique key, so re-processing the same release never duplicates (Rule 4).
 * Full clustering/dedup/confidence arrives in Phase 5 behind the same seam.
 */
class DeriveEventsFromEvidence(
    private val events: EventRepository,
    private val tx: TxRunner,
) {

    fun execute(endpoint: SourceEndpoint, touched: List<IngestEvidence.EvidenceTouch>): List<Event> {
        if (touched.isEmpty()) return emptyList()
        return tx.run {
            touched.mapNotNull { touch ->
                when (endpoint.endpointType) {
                    ENDPOINT_RELEASES -> upsertReleaseEvent(endpoint, touch)
                    else -> null // other endpoint types derive events in later phases
                }
            }
        }
    }

    private fun upsertReleaseEvent(
        endpoint: SourceEndpoint,
        touch: IngestEvidence.EvidenceTouch,
    ): Event? {
        val item = touch.item
        val provider = item.metadata["provider"] ?: "UNKNOWN"

        val existing = events.findByExternalRef(provider, item.externalId)
        val event = existing ?: run {
            val isPrerelease = item.metadata["prerelease"] == "true"
            val isDraft = item.metadata["draft"] == "true"
            val created = Event(
                id = UUID.randomUUID().toString(),
                type = EVENT_TYPE_SOFTWARE_RELEASE,
                title = item.title ?: "Release ${item.externalId}",
                summary = item.contentPreview,
                occurredAt = item.publishedAt,
                discoveredAt = java.time.Instant.now(),
                confidence = CONFIDENCE_HIGH, // first-party structured provider data
                significance = if (isPrerelease || isDraft) SIGNIFICANCE_LOW else SIGNIFICANCE_MEDIUM,
                status = EventStatus.CONFIRMED,
                externalRef = mapOf(
                    REF_PROVIDER to provider,
                    REF_EXTERNAL_ID to item.externalId,
                    REF_ENDPOINT_ID to endpoint.id,
                ),
                createdAt = java.time.Instant.now(),
                updatedAt = java.time.Instant.now(),
            )
            if (!events.insert(created)) {
                // Raced with another worker; treat as existing (idempotent).
                events.findByExternalRef(provider, item.externalId) ?: return null
            } else {
                created
            }
        }

        events.linkEvidence(event.id, touch.evidenceId)
        return event
    }

    companion object {
        const val EVENT_TYPE_SOFTWARE_RELEASE = "SOFTWARE_RELEASE"
        const val ENDPOINT_RELEASES = "RELEASES"
        const val REF_PROVIDER = "provider"
        const val REF_EXTERNAL_ID = "external_id"
        const val REF_ENDPOINT_ID = "endpoint_id"
        const val CONFIDENCE_HIGH = "HIGH"
        const val SIGNIFICANCE_MEDIUM = "MEDIUM"
        const val SIGNIFICANCE_LOW = "LOW"
    }
}
