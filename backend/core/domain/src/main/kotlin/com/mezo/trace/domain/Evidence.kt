package com.mezo.trace.domain

import java.time.Instant

/**
 * Immutable source-derived evidence (ADR-006, plan §4.6).
 *
 * Identity: (endpoint_id, external_id) — stable across polls. Content changes
 * append [EvidenceVersion]s; rows are never mutated in place. Enough
 * information is preserved to reconstruct exactly where the record came from.
 */
data class Evidence(
    val id: String,
    val sourceId: String,
    val endpointId: String,
    val externalId: String,
    val canonicalUrl: String?,
    val title: String?,
    val author: String?,
    val publishedAt: Instant?,
    val discoveredAt: Instant,
    val content: String?,
    val contentHash: String,
    val rawPayloadReference: String?,
    val metadata: Map<String, String>,
    val language: String?,
    val createdAt: Instant,
)

data class EvidenceVersion(
    val id: String,
    val evidenceId: String,
    val version: Int,
    val content: String?,
    val contentHash: String,
    val changedBy: String,
    val createdAt: Instant,
)
