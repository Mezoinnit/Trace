package com.mezo.trace.domain

import java.time.Instant

/**
 * A logical information source (global shared intelligence, ADR-001).
 * Identity: (provider, source_type, canonical_identity).
 */
data class Source(
    val id: String,
    val provider: Provider,
    val sourceType: String,
    val canonicalIdentity: String,
    val displayName: String?,
    val trust: TrustClassification,
    val verificationState: String,
    val capabilities: Set<Capability>,
    val status: SourceStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class SourceStatus { ACTIVE, DISABLED }
