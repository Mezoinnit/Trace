package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.application.port.EventRepository
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.application.port.SourceRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.usecase.evidence.IngestEvidence
import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import com.mezo.trace.domain.SourceEndpoint
import com.mezo.trace.domain.TrustClassification
import java.util.UUID

/**
 * Phase 4/5 event derivation from release evidence.
 *
 * - **Replay safety:** exact `external_ref` (provider + external_id) hits
 *   attach without duplicating (Rule 4).
 * - **Clustering (Phase 5):** items from OTHER providers describing the same
 *   release attach to the cluster representative via multi-signal similarity
 *   instead of creating duplicates (plan §13).
 * - **Confidence (Phase 5):** every create/attach recomputes an inspectable
 *   score with corroboration across distinct endpoints (plan §15).
 */
class DeriveEventsFromEvidence(
    private val events: EventRepository,
    private val sources: SourceRepository,
    private val clusterer: EventClusterer,
    private val resolveEntity: com.mezo.trace.application.usecase.entities.ResolveEntity,
    private val entities: com.mezo.trace.application.port.EntityRepository,
    private val audit: com.mezo.trace.application.port.AuditLogRepository,
    private val tx: TxRunner,
) {

    fun execute(endpoint: SourceEndpoint, touched: List<IngestEvidence.EvidenceTouch>): List<Event> {
        if (touched.isEmpty()) return emptyList()
        return tx.run {
            touched.mapNotNull { touch ->
                when (endpoint.endpointType) {
                    ENDPOINT_RELEASES -> upsertReleaseEvent(endpoint, touch)
                    ENDPOINT_PAGE, ENDPOINT_FEED -> upsertArticleEvent(endpoint, touch)
                    else -> null
                }
            }
        }
    }

    /** Articles become ARTICLE_PUBLISHED events, keyed by their stable
     *  external id (canonical URL / guid) within this source. */
    private fun upsertArticleEvent(
        endpoint: SourceEndpoint,
        touch: IngestEvidence.EvidenceTouch,
    ): Event? {
        val item = touch.item
        // Stable identity: adapter provider + external_id (URL/guid). Fallback
        // to the source's provider so missing metadata never collapses to
        // UNKNOWN and collide across sites (Rule 4).
        val provider = articleProvider(endpoint, item)

        events.findByExternalRef(provider, item.externalId)?.let { existing ->
            recomputeConfidence(existing.id, endpoint)
            return existing
        }

        val now = java.time.Instant.now()
        val trust = sourceTrust(endpoint.sourceId)
        val created = Event(
            id = UUID.randomUUID().toString(),
            type = EVENT_TYPE_ARTICLE_PUBLISHED,
            title = item.title ?: "Article ${item.externalId.take(12)}",
            summary = item.contentPreview,
            occurredAt = item.publishedAt,
            discoveredAt = now,
            confidence = CONFIDENCE_MEDIUM,
            significance = SIGNIFICANCE_MEDIUM,
            status = EventStatus.CONFIRMED,
            externalRef = mapOf(
                REF_PROVIDER to provider,
                REF_EXTERNAL_ID to item.externalId,
                REF_ENDPOINT_ID to endpoint.id,
            ),
            createdAt = now,
            updatedAt = now,
        )
        if (!events.insert(created)) {
            val raced = events.findByExternalRef(provider, item.externalId) ?: return null
            events.linkEvidence(raced.id, touch.evidenceId)
            return raced
        }
        events.linkEvidence(created.id, touch.evidenceId)

        val scored = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = trust,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = trust == TrustClassification.FIRST_PARTY ||
                    trust == TrustClassification.OFFICIAL,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        events.updateConfidence(created.id, scored.band, scored.score, scored.reasoning)
        return created.copy(
            confidenceScore = scored.score,
            confidenceReasoning = scored.reasoning,
            confidence = scored.band,
        )
    }

    private fun upsertReleaseEvent(
        endpoint: SourceEndpoint,
        touch: IngestEvidence.EvidenceTouch,
    ): Event? {
        val item = touch.item
        val provider = item.metadata["provider"] ?: "UNKNOWN"

        // 1. Exact provider identity → the same real-world release.
        events.findByExternalRef(provider, item.externalId)?.let { existing ->
            linkRepositoryEntity(existing.id, item)
            recomputeConfidence(existing.id, endpoint)
            return existing
        }

        // 2. Multi-signal clustering against recent same-type events.
        val decision = clusterer.findCluster(
            type = EVENT_TYPE_SOFTWARE_RELEASE,
            item = item,
            occurredAt = item.publishedAt,
            sourceId = endpoint.sourceId,
        )
        decision.attachTo?.let { representative ->
            events.linkEvidence(representative.id, touch.evidenceId)
            linkRepositoryEntity(representative.id, item)
            recomputeConfidence(representative.id, endpoint)
            return representative
        }

        // 3. Genuinely new event.
        val isPrerelease = item.metadata["prerelease"] == "true"
        val isDraft = item.metadata["draft"] == "true"
        val trust = sourceTrust(endpoint.sourceId)
        val created = Event(
            id = UUID.randomUUID().toString(),
            type = EVENT_TYPE_SOFTWARE_RELEASE,
            title = item.title ?: "Release ${item.externalId}",
            summary = item.contentPreview,
            occurredAt = item.publishedAt,
            discoveredAt = java.time.Instant.now(),
            confidence = CONFIDENCE_MEDIUM,
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
            val raced = events.findByExternalRef(provider, item.externalId) ?: return null
            events.linkEvidence(raced.id, touch.evidenceId)
            return raced
        }

        events.linkEvidence(created.id, touch.evidenceId)
        linkRepositoryEntity(created.id, item)

        // Seed confidence for the new event.
        val scored = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = trust,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = trust == TrustClassification.FIRST_PARTY ||
                    trust == TrustClassification.OFFICIAL,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        events.updateConfidence(created.id, scored.band, scored.score, scored.reasoning)
        return created.copy(
            confidenceScore = scored.score,
            confidenceReasoning = scored.reasoning,
            confidence = scored.band,
        )
    }

    /**
     * Phase 6: deterministic repository-entity resolution and event linkage.
     * GitHub release metadata carries owner/repo — a structured identifier,
     * so no AI extraction is needed.
     */
    private fun linkRepositoryEntity(eventId: String, item: com.mezo.trace.application.port.NormalizedItem) {
        val owner = item.metadata["owner"] ?: return
        val repo = item.metadata["repo"] ?: return
        val entity = resolveEntity.execute(
            com.mezo.trace.application.usecase.entities.ResolveEntity.Request(
                type = com.mezo.trace.domain.EntityType.REPOSITORY,
                canonicalName = "$owner/$repo",
                identifiers = listOf(
                    com.mezo.trace.domain.EntityIdentifier("GITHUB_REPO", "$owner/$repo"),
                ),
                aliases = listOf("$owner/$repo"),
                description = "GitHub repository",
            ),
        )
        entities.linkEvent(eventId, entity.id)
    }

    /** Recomputes inspectable confidence after evidence was attached. */
    private fun recomputeConfidence(eventId: String, endpoint: SourceEndpoint) {
        val endpoints = events.countDistinctSupportingEndpoints(eventId)
        val event = events.findById(eventId) ?: return
        val trust = sourceTrust(endpoint.sourceId)

        val scored = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = trust,
                distinctSupportingEndpoints = endpoints,
                firstPartyConfirmation = trust == TrustClassification.FIRST_PARTY ||
                    trust == TrustClassification.OFFICIAL,
                contradicted = event.status == EventStatus.CONTESTED,
                hasStableExternalId = true,
            ),
        )
        if (scored.band != event.confidence) {
            // Historical intelligence is never silently adjusted (Rule 5):
            // band transitions are auditable timeline entries.
            audit.append(
                com.mezo.trace.application.model.AuditEntry(
                    action = "CONFIDENCE_UPDATED",
                    actorType = com.mezo.trace.application.model.ActorType.JOB,
                    resourceType = "event",
                    resourceId = eventId,
                    metadata = mapOf(
                        "band_from" to event.confidence,
                        "band_to" to scored.band,
                        "score" to scored.score.toString(),
                    ),
                ),
            )
        }
        events.updateConfidence(eventId, scored.band, scored.score, scored.reasoning)
    }

    private fun sourceTrust(sourceId: String): TrustClassification =
        sources.findById(sourceId)?.trust ?: TrustClassification.UNKNOWN

    private fun articleProvider(endpoint: SourceEndpoint, item: NormalizedItem): String =
        item.metadata["provider"]
            ?: sources.findById(endpoint.sourceId)?.provider?.name
            ?: when (endpoint.adapter) {
                "rss" -> "RSS"
                "atom" -> "ATOM"
                "web_page" -> "WEB_PAGE"
                else -> TrustClassification.UNKNOWN.name
            }

    companion object {
        const val EVENT_TYPE_SOFTWARE_RELEASE = "SOFTWARE_RELEASE"
        const val EVENT_TYPE_ARTICLE_PUBLISHED = "ARTICLE_PUBLISHED"
        const val ENDPOINT_RELEASES = "RELEASES"
        const val ENDPOINT_PAGE = "PAGE"
        const val ENDPOINT_FEED = "FEED"
        const val REF_PROVIDER = "provider"
        const val REF_EXTERNAL_ID = "external_id"
        const val REF_ENDPOINT_ID = "endpoint_id"
        const val CONFIDENCE_MEDIUM = "MEDIUM"
        const val SIGNIFICANCE_MEDIUM = "MEDIUM"
        const val SIGNIFICANCE_LOW = "LOW"
    }
}
