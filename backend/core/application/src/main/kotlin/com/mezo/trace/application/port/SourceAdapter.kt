package com.mezo.trace.application.port

import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider
import java.time.Instant

/**
 * One normalized, validated source item — the input to evidence creation in
 * Phase 3. Content is compact by default (plan §12): preview only.
 */
data class NormalizedItem(
    val externalId: String,
    val canonicalUrl: String?,
    val title: String?,
    val author: String?,
    val publishedAt: Instant?,
    val contentPreview: String?,
    val language: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Result of one adapter fetch cycle. Bounded: items per cycle are capped by
 * each adapter (Rule 6).
 */
data class FetchResult(
    val items: List<NormalizedItem>,
    val nextCursor: String? = null,
    val etag: String? = null,
)

/** Thrown when an endpoint cannot be interpreted as this adapter's resource. */
class EndpointRejected(message: String) : RuntimeException(message)

/**
 * The stable adapter contract (SOURCE_ADAPTERS.md, ADR-005). Implementations
 * live in the `adapters` module; selection is provider-driven.
 */
interface SourceAdapter {
    /** Adapter identifier persisted on endpoints (e.g. "github"). */
    val id: String

    val provider: Provider

    /** True when this adapter can treat [endpointUri] as its exact resource. */
    fun supports(endpointUri: String): Boolean

    fun capabilitiesFor(endpointUri: String): Set<Capability>

    /**
     * Fetches and normalizes new items for an exact endpoint.
     *
     * @throws EndpointRejected when the URI is invalid for this adapter.
     * @throws FetchFailure propagated from transport failures.
     */
    fun fetch(endpointUri: String, cursor: String?, knownEtag: String?): FetchResult
}

/**
 * Selection port implemented by the adapter registry. The application layer
 * never references concrete adapters. Feed-vs-page URIs are ambiguous by
 * shape alone, so confirmed registrations must select by stored [byId] id;
 * [forUri] is a best-effort default for probing paths.
 */
interface AdapterSelector {
    fun forUri(endpointUri: String): SourceAdapter?
    fun byId(id: String): SourceAdapter?
}
