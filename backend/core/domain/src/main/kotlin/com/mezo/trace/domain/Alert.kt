package com.mezo.trace.domain

import java.time.Instant

/**
 * Created when an Event matches a Watch (plan §4.15). Uniqueness of
 * (watch_id, event_id) guarantees exactly-once alerting (ADR-012).
 */
data class Alert(
    val id: String,
    val workspaceId: String,
    val watchId: String,
    val eventId: String,
    val relevance: Map<String, String>,
    val significance: String,
    val status: AlertStatus,
    val createdAt: Instant,
)

enum class AlertStatus { PENDING, NOTIFIED, READ }
