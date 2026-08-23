package com.mezo.trace.application.port

import com.mezo.trace.domain.Alert
import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import com.mezo.trace.domain.Watch
import java.time.Instant

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

    fun findById(id: String): Event?

    /**
     * Cursor-paginated feed, newest first (plan §28), keyed by
     * `(occurred_at DESC NULLS LAST, id DESC)`.
     */
    fun feedPage(cursor: String?, limit: Int): EventFeedPage

    fun latestDiscovered(limit: Int): List<Event>
}

interface WatchRepository {

    fun insert(watch: Watch)
    fun findById(id: String): Watch?
    fun listForWorkspace(workspaceId: String): List<Watch>

    /** All active watches whose endpoint constraint includes [endpointId]. */
    fun findActiveForEndpoint(endpointId: String): List<Watch>

    fun setStatus(id: String, status: String, now: Instant)
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
