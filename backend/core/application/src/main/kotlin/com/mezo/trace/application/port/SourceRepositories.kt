package com.mezo.trace.application.port

import com.mezo.trace.domain.Source
import com.mezo.trace.domain.SourceEndpoint
import com.mezo.trace.domain.SourceHealth
import java.time.Instant

interface SourceRepository {
    /** Inserts a source; returns false when the canonical identity already exists. */
    fun insert(source: Source): Boolean
    fun findById(id: String): Source?
    fun findByCanonicalIdentity(provider: String, sourceType: String, canonicalIdentity: String): Source?
    fun listAll(): List<Source>
    fun setStatus(id: String, status: String, now: Instant)
}

interface SourceEndpointRepository {
    /** Inserts an endpoint; returns false when the endpoint URI already exists. */
    fun insert(endpoint: SourceEndpoint): Boolean
    fun findById(id: String): SourceEndpoint?
    fun findByUri(endpointUri: String): SourceEndpoint?
    fun listBySourceId(sourceId: String): List<SourceEndpoint>
    fun setStatus(id: String, status: String, now: Instant)

    /** ACTIVE endpoints whose next_poll_at is due (scheduler scan, ADR-009). */
    fun findDueEndpointIds(now: Instant, limit: Int): List<String>
}

/**
 * Health state transitions (plan §9). Failures are recorded explicitly —
 * never silently dropped (Rule 5, Rule 7).
 */
interface SourceHealthRepository {

    fun initialize(endpointId: String)

    fun findByEndpointId(endpointId: String): SourceHealth?

    fun applySuccess(endpointId: String, at: Instant, latencyMs: Int, itemCount: Int)

    /**
     * Records a fetch failure; [failureState] is the derived health state
     * (RATE_LIMITED, NETWORK_FAILED, PARSER_FAILED, …).
     */
    fun applyFailure(endpointId: String, at: Instant, failureState: String)

    fun storeEtag(endpointId: String, etag: String?)

    /** Stores the computed polling interval and failure streak (ADR-009). */
    fun applyInterval(endpointId: String, interval: java.time.Duration, consecutiveFailures: Int, at: Instant)

    /**
     * HEALTHY endpoints whose last success predates [olderThan] become STALE
     * (plan §9): monitoring never stops silently, and the next successful
     * fetch restores HEALTHY.
     */
    fun markStaleIfOverdue(olderThan: Instant, now: Instant): Int

    /**
     * Records extraction outcomes (Phase 13, plan §8 step 12).
     *
     * [notes] are the adapter's parser/discovery notes for this cycle. A failed
     * outcome (no extractable content, robots refusal) sets PARSER_FAILED and
     * retains the detail in `last_parser_error`. A non-failed outcome only
     * records the notes and clears the previous parser error — successful
     * fetches must never degrade, or silently rewrite, endpoint health
     * (Rule 7: source health is always visible).
     */
    fun applyParserOutcome(endpointId: String, at: Instant, notes: List<String>, failed: Boolean)
}
