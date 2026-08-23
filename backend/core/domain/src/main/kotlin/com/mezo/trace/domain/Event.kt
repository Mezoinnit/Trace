package com.mezo.trace.domain

import java.time.Instant

/**
 * Something that happened (EVENT_MODEL.md). Phase 4 creates deterministic
 * SOFTWARE_RELEASE events from GitHub release evidence; Phase 5 adds
 * clustering, inspectable confidence scoring, and contradiction states.
 */
data class Event(
    val id: String,
    val type: String,
    val title: String,
    val summary: String?,
    val occurredAt: Instant?,
    val discoveredAt: Instant,
    val confidence: String,
    val significance: String,
    val status: EventStatus,
    /** Stable provider identity for pipeline-derived events. */
    val externalRef: Map<String, String>,
    /** Inspectable confidence inputs — never a magic number (plan §15). */
    val confidenceScore: Double? = null,
    val confidenceReasoning: Map<String, Double> = emptyMap(),
    /** Set when this duplicate was merged into a cluster representative. */
    val mergedIntoEventId: String? = null,
    val createdAt: Instant = java.time.Instant.EPOCH,
    val updatedAt: Instant = java.time.Instant.EPOCH,
)

enum class EventStatus { CONFIRMED, CONTESTED, CORRECTED, SUPERSEDED }
