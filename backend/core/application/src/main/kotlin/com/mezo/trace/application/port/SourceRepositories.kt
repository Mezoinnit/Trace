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
}
