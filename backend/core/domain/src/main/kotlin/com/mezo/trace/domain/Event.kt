package com.mezo.trace.domain

import java.time.Instant

/**
 * Something that happened (EVENT_MODEL.md). Phase 4 creates deterministic
 * SOFTWARE_RELEASE events from GitHub release evidence; later phases add
 * clustering, confidence scoring, and contradiction states.
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
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class EventStatus { CONFIRMED, CONTESTED, CORRECTED, SUPERSEDED }
