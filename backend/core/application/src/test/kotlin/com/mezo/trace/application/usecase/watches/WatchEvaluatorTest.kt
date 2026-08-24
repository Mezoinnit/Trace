package com.mezo.trace.application.usecase.watches

import com.mezo.trace.domain.Event
import com.mezo.trace.domain.EventStatus
import com.mezo.trace.domain.Watch
import com.mezo.trace.domain.WatchStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchEvaluatorTest {

    private val now: Instant = Instant.parse("2026-08-23T12:00:00Z")

    private fun watch(
        entityIds: Set<String> = emptySet(),
        eventTypes: Set<String> = emptySet(),
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        maxAgeHours: Int? = null,
        minSignificance: String? = null,
        minConfidence: String? = null,
        endpoints: Set<String> = setOf("ep-1"),
    ) = Watch(
        id = "w", workspaceId = "ws", createdBy = "u", name = "t",
        endpointIds = endpoints, entityIds = entityIds, eventTypes = eventTypes,
        keywordsInclude = include, keywordsExclude = exclude,
        maxAgeHours = maxAgeHours, minSignificance = minSignificance,
        minConfidence = minConfidence,
        status = WatchStatus.ACTIVE, version = 1,
        createdAt = now, updatedAt = now,
    )

    private fun event(
        title: String = "Widget 3.0 released",
        summary: String? = null,
        type: String = "SOFTWARE_RELEASE",
        significance: String = "MEDIUM",
        confidence: String = "HIGH",
        occurredAt: Instant? = now.minusSeconds(3600),
        discoveredAt: Instant = now,
        endpointId: String = "ep-1",
    ) = Event(
        id = UUID.randomUUID().toString(), type = type, title = title, summary = summary,
        occurredAt = occurredAt, discoveredAt = discoveredAt, confidence = confidence,
        significance = significance, status = EventStatus.CONFIRMED,
        externalRef = mapOf("endpoint_id" to endpointId),
    )

    @Test
    fun `unconstrained watch matches`() {
        val result = WatchEvaluator.evaluate(
            watch = watch(), event = event(), now = now)
        assertTrue(result.matched)
    }

    @Test
    fun `endpoint containment is enforced`() {
        val result = WatchEvaluator.evaluate(
            watch = watch(), event = event(endpointId = "other"), now = now)
        assertFalse(result.matched)
        assertTrue(result.rejectedBecause.contains("endpoint_not_watched"))
    }

    @Test
    fun `keyword include requires a hit and exclude blocks on any hit`() {
        val miss = WatchEvaluator.evaluate(
            watch = watch(include = listOf("kotlin")), event = event(), now = now)
        assertFalse(miss.matched)
        assertTrue(miss.rejectedBecause.contains("keyword_include_miss"))

        val hit = WatchEvaluator.evaluate(
            watch = watch(include = listOf("WIDGET")), event = event(), now = now)
        assertTrue(hit.matched)

        val blocked = WatchEvaluator.evaluate(
            watch = watch(exclude = listOf("beta")), event = event(summary = "beta quality"), now = now)
        assertFalse(blocked.matched)
        assertTrue(blocked.rejectedBecause.contains("keyword_exclude_hit"))

        // Case-insensitive matching across title and summary.
        val caseHit = WatchEvaluator.evaluate(
            watch = watch(include = listOf("released")), event = event(title = "WIDGET 3.0 RELEASED"), now = now)
        assertTrue(caseHit.matched)
    }

    @Test
    fun `significance and confidence thresholds compare band order`() {
        val sigBlocked = WatchEvaluator.evaluate(
            watch = watch(minSignificance = "HIGH"), event = event(significance = "LOW"), now = now)
        assertEquals(listOf("significance_below_threshold"), sigBlocked.rejectedBecause)

        val sigPass = WatchEvaluator.evaluate(
            watch = watch(minSignificance = "high"), event = event(significance = "CRITICAL"), now = now)
        assertTrue(sigPass.matched)

        val confBlocked = WatchEvaluator.evaluate(
            watch = watch(minConfidence = "VERY_HIGH"), event = event(confidence = "MEDIUM"), now = now)
        assertTrue(confBlocked.rejectedBecause.contains("confidence_below_threshold"))
    }

    @Test
    fun `time window uses occurred_at falling back to discovered_at`() {
        val old = WatchEvaluator.evaluate(
            watch = watch(maxAgeHours = 24),
            event = event(occurredAt = now.minusSeconds(72 * 3600)),
            now = now,
        )
        assertTrue(old.rejectedBecause.contains("outside_time_window"))

        val fresh = WatchEvaluator.evaluate(
            watch = watch(maxAgeHours = 24), event = event(), now = now)
        assertTrue(fresh.matched)

        val noOccurredAt = WatchEvaluator.evaluate(
            watch = watch(maxAgeHours = 24),
            event = event(occurredAt = null, discoveredAt = now.minusSeconds(48 * 3600)),
            now = now,
        )
        assertTrue(noOccurredAt.rejectedBecause.contains("outside_time_window"))
    }

    @Test
    fun `entity constraint passes when any linked entity is watched`() {
        val pass = WatchEvaluator.evaluate(
            watch(entityIds = setOf("e-compose")),
            event(),
            linkedEntityIds = setOf("e-other", "e-compose"),
            now = now,
        )
        assertTrue(pass.matched)

        val fail = WatchEvaluator.evaluate(
            watch(entityIds = setOf("e-compose")),
            event(),
            linkedEntityIds = setOf("e-other"),
            now = now,
        )
        assertTrue(fail.rejectedBecause.contains("no_watched_entity"))
    }

    @Test
    fun `multiple rejections accumulate reasons`() {
        val result = WatchEvaluator.evaluate(
            watch(minSignificance = "CRITICAL", include = listOf("security")),
            event(significance = "LOW"),
        )
        assertEquals(
            setOf("significance_below_threshold", "keyword_include_miss"),
            result.rejectedBecause.toSet(),
        )
    }
}
