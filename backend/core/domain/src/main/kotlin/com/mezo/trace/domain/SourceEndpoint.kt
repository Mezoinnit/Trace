package com.mezo.trace.domain

import java.time.Instant

/**
 * The exact monitored resource. The endpoint URI is the stable identity —
 * never a display URL (plan §4.4). Examples:
 *   github://androidx/androidx/releases
 *   https://example.com/feed.xml
 */
data class SourceEndpoint(
    val id: String,
    val sourceId: String,
    val endpointUri: String,
    val displayUrl: String?,
    val endpointType: String,
    val capabilities: Set<Capability>,
    val adapter: String,
    val status: SourceStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Per-endpoint health record (plan §9).
 */
data class SourceHealth(
    val endpointId: String,
    val state: HealthState,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val failureCount: Long,
    val consecutiveFailures: Int,
    val averageLatencyMs: Int?,
    val lastItemAt: Instant?,
    val lastChangeAt: Instant?,
    /** Stored validator for conditional polling (ETag). */
    val lastEtag: String? = null,
)
