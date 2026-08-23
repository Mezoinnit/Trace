package com.mezo.trace.domain

import java.time.Instant

/**
 * Persistent monitoring intent (WATCH_MODEL.md). Phase 4 supports the
 * deterministic core: endpoint constraints + event-type filters. Keyword and
 * semantic filters arrive in Phase 7 behind the same shape.
 */
data class Watch(
    val id: String,
    val workspaceId: String,
    val createdBy: String,
    val name: String,
    val endpointIds: Set<String>,
    val eventTypes: Set<String>,
    val status: WatchStatus,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class WatchStatus { ACTIVE, PAUSED, DELETED }
