package com.mezo.trace.domain

import java.time.Instant

/**
 * Persistent monitoring intent (WATCH_MODEL.md).
 *
 * Phase 7 rule set: endpoint constraints, entity constraints, event types,
 * keyword include/exclude, time window, significance/confidence thresholds.
 * The semantic description is stored but only evaluated from Phase 10
 * (AI layer) — deterministic matching never depends on it.
 */
data class Watch(
    val id: String,
    val workspaceId: String,
    val createdBy: String,
    val name: String,
    val endpointIds: Set<String>,
    val entityIds: Set<String> = emptySet(),
    val eventTypes: Set<String> = emptySet(),
    /** Include: at least one term must appear in title/summary. */
    val keywordsInclude: List<String> = emptyList(),
    /** Exclude: no term may appear in title/summary. */
    val keywordsExclude: List<String> = emptyList(),
    /** Stored for Phase 10 semantic evaluation; not matched deterministically. */
    val semantic: String? = null,
    /** Event recency window; null = no time constraint. */
    val maxAgeHours: Int? = null,
    /** Minimum significance band to alert; null = any. */
    val minSignificance: String? = null,
    /** Minimum confidence band to alert; null = any. */
    val minConfidence: String? = null,
    /** §4.14 notification policy. DIGEST is reserved for the FCM phase. */
    val notificationMode: NotificationMode = NotificationMode.IMMEDIATE,
    val status: WatchStatus,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class WatchStatus { ACTIVE, PAUSED, DELETED }

enum class NotificationMode { IMMEDIATE, DIGEST, NEVER }
