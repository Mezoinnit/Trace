package com.mezo.trace.application.usecase.watches

import com.mezo.trace.domain.Event
import com.mezo.trace.domain.Watch
import java.time.Instant

/**
 * Deterministic watch-matching evaluation (plan §4.15, WATCH_MODEL.md §2).
 *
 * Pure function: (watch, event, linked entity ids, now) → matched? + reasons.
 * All signals are rule-based — no single threshold decides alone, and the
 * semantic field is deliberately NOT evaluated here until the AI layer
 * exists (Phase 10). Re-evaluation is safe: alert idempotency is enforced by
 * the (watch_id, event_id) unique constraint regardless of this result.
 */
object WatchEvaluator {

    /** Ordered bands shared with the confidence/significance columns. */
    private val SIGNIFICANCE_RANK = listOf("TRIVIAL", "LOW", "MEDIUM", "HIGH", "CRITICAL")
    private val CONFIDENCE_RANK = listOf("VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH")

    data class Result(
        val matched: Boolean,
        /** Human-readable reasons for non-match; empty when matched. */
        val rejectedBecause: List<String>,
    )

    fun evaluate(
        watch: Watch,
        event: Event,
        linkedEntityIds: Set<String> = emptySet(),
        now: Instant = Instant.now(),
    ): Result {
        val rejections = mutableListOf<String>()

        // 1. Endpoint containment (hard constraint — watches are bound to
        //    exact endpoints and are never silently broadened).
        val eventEndpoint = event.externalRef["endpoint_id"]
        if (eventEndpoint != null && eventEndpoint !in watch.endpointIds) {
            rejections += "endpoint_not_watched"
        }

        // 2. Event type filter; empty means all types.
        if (watch.eventTypes.isNotEmpty() && event.type !in watch.eventTypes) {
            rejections += "event_type_${event.type}"
        }

        // 3. Entity constraint: at least one linked entity must be watched.
        if (watch.entityIds.isNotEmpty() && watch.entityIds.intersect(linkedEntityIds).isEmpty()) {
            rejections += "no_watched_entity"
        }

        // 4. Significance threshold.
        watch.minSignificance?.let { threshold ->
            if (rankOf(event.significance, SIGNIFICANCE_RANK) <
                rankOf(threshold.uppercase(), SIGNIFICANCE_RANK)
            ) {
                rejections += "significance_below_threshold"
            }
        }

        // 5. Confidence threshold.
        watch.minConfidence?.let { threshold ->
            if (rankOf(event.confidence, CONFIDENCE_RANK) <
                rankOf(threshold.uppercase(), CONFIDENCE_RANK)
            ) {
                rejections += "confidence_below_threshold"
            }
        }

        // 6. Time window: prefer occurred_at, fall back to discovered_at.
        watch.maxAgeHours?.let { hours ->
            val reference = event.occurredAt ?: event.discoveredAt
            if (reference.isBefore(now.minusSeconds(hours.toLong() * 3600))) {
                rejections += "outside_time_window"
            }
        }

        // 7. Keyword filters over title + summary, case-insensitive.
        val haystack = haystack(event)
        if (watch.keywordsInclude.isNotEmpty() &&
            watch.keywordsInclude.none { term -> haystack.contains(term.lowercase()) }
        ) {
            rejections += "keyword_include_miss"
        }
        if (watch.keywordsExclude.isNotEmpty() &&
            watch.keywordsExclude.any { term -> term.isNotBlank() && haystack.contains(term.lowercase()) }
        ) {
            rejections += "keyword_exclude_hit"
        }

        return Result(rejections.isEmpty(), rejections)
    }

    /** Searchable text for keyword filters: title + summary, lowercased once. */
    private fun haystack(event: Event): String =
        ((event.title.orEmpty()) + "\n" + (event.summary.orEmpty())).lowercase()

    private fun rankOf(value: String?, rank: List<String>): Int =
        value?.let { v -> rank.indexOf(v.uppercase()).takeIf { it >= 0 } } ?: -1
}
