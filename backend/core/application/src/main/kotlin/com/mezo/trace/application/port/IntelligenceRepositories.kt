package com.mezo.trace.application.port

import com.mezo.trace.domain.Alert
import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import com.mezo.trace.domain.Watch
import java.time.Instant

/**
 * Supporting evidence as exposed on event detail (plan §3.5) with source
 * attribution — the traceability surface for "where did this come from".
 */
data class EventEvidenceView(
    val evidenceId: String,
    val title: String?,
    val canonicalUrl: String?,
    val role: String,
    val sourceName: String?,
    val sourceTrust: String,
    val publishedAt: java.time.Instant?,
)

/** Structured search filters (plan §11 phase: time/source/type/entity). */
data class EventSearchFilters(
    val type: String? = null,
    val sourceId: String? = null,
    val entityId: String? = null,
    val from: java.time.Instant? = null,
    val to: java.time.Instant? = null,
)

/** One deterministic feed page (plan §28). */
data class EventFeedPage(
    val events: List<Event>,
    /** Opaque; null when no more results. */
    val nextCursor: String?,
)

interface EventRepository {

    /**
     * Finds a pipeline-derived event by its stable provider identity
     * (external_ref provider+external_id) — the replay-safety key.
     */
    fun findByExternalRef(provider: String, externalId: String): Event?

    fun insert(event: Event): Boolean

    fun linkEvidence(eventId: String, evidenceId: String, role: String = "SUPPORTING")

    /** Supporting/contradicting evidence with source attribution. */
    fun evidenceForEvent(eventId: String): List<EventEvidenceView>

    fun updateStatus(eventId: String, status: EventStatus)

    /** Sets (or strengthens) the evidence role on the event-evidence link. */
    fun markEvidenceRole(evidenceId: String, eventId: String, role: String)

    /** Distinct endpoints contributing SUPPORTING evidence — corroboration signal. */
    fun countDistinctSupportingEndpoints(eventId: String): Int

    /** Stores a recomputed confidence band/score/reasoning on an event. */
    fun updateConfidence(eventId: String, band: String, score: Double, reasoning: Map<String, Double>)

    fun findById(id: String): Event?

    /**
     * Cursor-paginated feed, newest first (plan §28), keyed by
     * `(occurred_at DESC NULLS LAST, id DESC)`.
     */
    fun feedPage(cursor: String?, limit: Int): EventFeedPage

    fun latestDiscovered(limit: Int): List<Event>

    /**
     * Full-text event search ranked by relevance (plan §29).
     * Filters compose; empty query matches everything the filters allow.
     */
    fun searchEvents(query: String, filters: EventSearchFilters, limit: Int): List<Event>

    /** Recent events with evidence contributed by this source (clustering scope). */
    fun latestDiscoveredBySource(sourceId: String, limit: Int): List<Event>
}

interface WatchRepository {

    fun insert(watch: Watch)
    fun findById(id: String): Watch?
    fun listForWorkspace(workspaceId: String): List<Watch>

    /** All active watches whose endpoint constraint includes [endpointId]. */
    fun findActiveForEndpoint(endpointId: String): List<Watch>

    fun setStatus(id: String, status: String, now: Instant)

    /** Full-row update; caller owns optimistic-lock checks (plan §24). */
    fun update(watch: Watch)
}

interface AlertRepository {

    /**
     * Inserts an alert; returns null when (watch_id, event_id) already exists
     * — exactly-once semantics (ADR-012), never an error.
     */
    fun insertIfAbsent(alert: Alert): Alert?

    fun listForWorkspace(workspaceId: String, limit: Int): List<Alert>

    fun countForEvent(eventId: String): Int
}

/** Status transition helper shared by pipeline writers. */
fun eventStatusOrDefault(statusName: String?): EventStatus =
    statusName?.let { runCatching { EventStatus.valueOf(it) }.getOrNull() } ?: EventStatus.CONFIRMED
