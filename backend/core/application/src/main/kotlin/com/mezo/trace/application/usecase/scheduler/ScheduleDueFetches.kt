package com.mezo.trace.application.usecase.scheduler

import com.mezo.trace.domain.JobStatus
import com.mezo.trace.domain.JobTypes
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.JobRepository
import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceHealthRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.Job
import java.time.Duration
import java.util.UUID

/**
 * Scheduler pass (ADR-009): finds endpoints whose `next_poll_at` is due and
 * enqueues exactly one bounded FETCH_SOURCE job each, advancing the schedule
 * with adaptive intervals + jitter so many endpoints never poll in lockstep.
 *
 * A due endpoint with an already-pending job is skipped (Rule 6: no queue
 * flooding while workers are saturated).
 */
class ScheduleDueFetches(
    private val endpoints: SourceEndpointRepository,
    private val health: SourceHealthRepository,
    private val jobs: JobRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
    private val policy: IntervalCalculator.Policy = IntervalCalculator.Policy(),
) {

    data class PassSummary(
        val scannedEndpoints: Int = 0,
        val enqueued: Int = 0,
        val skippedPending: Int = 0,
        val initialized: Int = 0,
    )

    fun execute(batchLimit: Int = 200): PassSummary {
        val now = clock.now()

        // §9: sources that have gone quiet are marked STALE so users and
        // operators see them; the next success restores HEALTHY.
        tx.run { health.markStaleIfOverdue(now.minus(STALE_AFTER), now) }

        var enqueued = 0
        var skipped = 0
        var initialized = 0

        val dueIds = listDueEndpointIds(now, batchLimit)

        for (endpointId in dueIds) {
            tx.run {
                val endpoint = endpoints.findById(endpointId) ?: return@run

                // First sight: initialize health with a base interval.
                if (health.findByEndpointId(endpointId) == null) {
                    health.initialize(endpointId)
                    initialized++
                }

                if (jobs.hasPendingForType(JobTypes.FETCH_SOURCE, "endpoint_id", endpointId)) {
                    skipped++
                    return@run
                }

                jobs.enqueue(
                    Job(
                        id = UUID.randomUUID().toString(),
                        type = JobTypes.FETCH_SOURCE,
                        payload = mapOf(
                            "endpoint_id" to endpointId,
                            "source_id" to endpoint.sourceId,
                        ),
                        maxAttempts = 5,
                        status = JobStatus.QUEUED,
                        availableAt = now,
                        idempotencyKey = "fetch:$endpointId:${now.epochSecond / 60}",
                    ),
                )
                enqueued++

                // Advance the schedule optimistically; the worker refines it
                // again after the actual fetch outcome.
                advanceSchedule(endpointId, IntervalCalculator.Outcome.SUCCESS_EMPTY, null, now)
            }
        }
        return PassSummary(dueIds.size, enqueued, skipped, initialized)
    }

    /** Refines next_poll_at after a known outcome (called by the worker). */
    fun advanceSchedule(
        endpointId: String,
        outcome: IntervalCalculator.Outcome,
        previousInterval: Duration?,
        now: java.time.Instant,
    ): Duration = tx.run {
        val current = health.findByEndpointId(endpointId)
        val failures = when (outcome) {
            IntervalCalculator.Outcome.FAILURE, IntervalCalculator.Outcome.RATE_LIMITED ->
                (current?.consecutiveFailures ?: 0) + 1
            else -> 0
        }
        val interval = IntervalCalculator.nextInterval(outcome, failures, previousInterval, policy)
        health.applyInterval(endpointId, interval, failures, now)
        interval
    }

    private fun listDueEndpointIds(now: java.time.Instant, limit: Int): List<String> =
        tx.run { endpoints.findDueEndpointIds(now, limit) }

    companion object {
        /** A source quiet this long is surfaced as STALE (plan §9). */
        val STALE_AFTER: java.time.Duration = java.time.Duration.ofHours(48)
    }
}
