package com.mezo.trace.server.worker

import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.JobRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.usecase.scheduler.IntervalCalculator
import com.mezo.trace.application.usecase.scheduler.ScheduleDueFetches
import com.mezo.trace.domain.Job
import com.mezo.trace.domain.JobTypes
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}

/**
 * Job execution loop (ADR-008, plan §47–48).
 *
 * - claims a bounded batch per tick (FOR UPDATE SKIP LOCKED)
 * - bounded concurrency inside the batch
 * - retries with exponential backoff; dead-letters after max attempts
 * - expired leases are requeued (visibility timeout)
 */
class JobLoop(
    private val jobs: JobRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
    private val fetchHandler: suspend (endpointId: String) -> IntervalCalculator.Outcome,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val leaseTtl: Duration = DEFAULT_LEASE_TTL,
    private val workerId: String = "worker-" + java.util.UUID.randomUUID().toString().take(8),
) {

    data class TickResult(val claimed: Int, val completed: Int, val retried: Int, val dead: Int)

    suspend fun tick(): TickResult {
        // Visibility timeout: reclaim abandoned leases first.
        val now = clock.now()
        tx.run { jobs.requeueExpiredLeases(now.minus(leaseTtl), now) }

        val batch = tx.run { jobs.claim(workerId, batchSize, now) }
        if (batch.isEmpty()) return TickResult(0, 0, 0, 0)

        var completed = 0
        var retried = 0
        var dead = 0

        coroutineScope {
            batch.chunked(concurrency).forEach { chunk ->
                chunk.map { job ->
                    async { process(job) }
                }.awaitAll().forEach { outcome ->
                    when (outcome) {
                        is ProcessResult.Completed -> completed++
                        is ProcessResult.Retry -> retried++
                        is ProcessResult.Dead -> dead++
                    }
                }
            }
        }
        return TickResult(batch.size, completed, retried, dead)
    }

    private sealed interface ProcessResult {
        object Completed : ProcessResult
        object Retry : ProcessResult
        object Dead : ProcessResult
    }

    private suspend fun process(job: Job): ProcessResult {
        return try {
            when (job.type) {
                JobTypes.FETCH_SOURCE -> handleFetch(job)
                else -> unknownType(job)
            }
        } catch (t: Throwable) {
            fail(job, "INTERNAL", t.message ?: "unexpected error")
        }
    }

    private suspend fun handleFetch(job: Job): ProcessResult {
        val endpointId = job.payload["endpoint_id"]
            ?: return fail(job, "BAD_PAYLOAD", "missing endpoint_id")

        return when (val outcome = fetchHandler(endpointId)) {
            IntervalCalculator.Outcome.ITEMS_FOUND,
            IntervalCalculator.Outcome.SUCCESS_EMPTY -> {
                tx.run { jobs.markCompleted(job.id, clock.now()) }
                logger.debug { "job ${job.id} completed ($outcome)" }
                ProcessResult.Completed
            }

            IntervalCalculator.Outcome.FAILURE -> fail(
                job, "FETCH_FAILED", "source fetch failed",
            )

            IntervalCalculator.Outcome.RATE_LIMITED -> fail(
                job, "RATE_LIMITED", "provider rate limit",
            )
        }
    }

    private fun unknownType(job: Job): ProcessResult =
        fail(job, "UNKNOWN_TYPE", "no handler for job type ${job.type}")

    private fun fail(job: Job, code: String, message: String): ProcessResult {
        val backoff = Duration.ofSeconds(
            BACKOFF_BASE_SECONDS * (1L shl (job.attempts - 1).coerceIn(0, 15)),
        )
        var dead = false
        tx.run {
            dead = jobs.markFailed(job.id, code, message, clock.now().plus(backoff), clock.now())
        }
        logger.warn { "job ${job.id} failed ($code); ${if (dead) "dead-lettered" else "retry in ${backoff.seconds}s"}" }
        return if (dead) ProcessResult.Dead else ProcessResult.Retry
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 4
        const val DEFAULT_BATCH_SIZE = 16
        val DEFAULT_LEASE_TTL: Duration = Duration.ofMinutes(10)
        const val BACKOFF_BASE_SECONDS = 30L
    }
}

/**
 * Scheduler cadence helper: runs [ScheduleDueFetches] every pass.
 * Kept separate from the worker so the two scale independently.
 */
class SchedulerLoop(
    private val scheduleDueFetches: ScheduleDueFetches,
    private val jobs: JobRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
) {
    fun tick(): com.mezo.trace.application.usecase.scheduler.ScheduleDueFetches.PassSummary =
        scheduleDueFetches.execute()
}
