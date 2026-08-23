package com.mezo.trace.application.port

import com.mezo.trace.domain.Job

/**
 * Durable job queue operations (ADR-008). All methods run inside the
 * caller's transaction; claiming uses FOR UPDATE SKIP LOCKED so concurrent
 * workers never receive the same job.
 */
interface JobRepository {

    /**
     * Enqueues a job. When [Job.idempotencyKey] is set and a QUEUED/LOCKED
     * job with the same key exists, returns null instead of duplicating.
     */
    fun enqueue(job: Job): Job?

    /** Claims up to [limit] due jobs for [workerId]; sets LOCKED + lease. */
    fun claim(workerId: String, limit: Int, now: java.time.Instant): List<Job>

    fun markCompleted(jobId: String, now: java.time.Instant)

    /**
     * Records a failure. When attempts remain, the job returns to QUEUED with
     * exponential backoff applied by the caller via [availableAt]; otherwise
     * it becomes DEAD (Rule 5: failures are recorded, never dropped).
     */
    fun markFailed(jobId: String, errorCode: String?, errorMessage: String?, availableAt: java.time.Instant, now: java.time.Instant): Boolean

    /** True when no attempts remain after this failure. */
    fun exhausted(jobId: String): Boolean

    fun countByStatus(status: String): Long

    /** Requeues jobs whose lease expired (visibility timeout). */
    fun requeueExpiredLeases(olderThan: java.time.Instant, now: java.time.Instant): Int

    fun hasPendingForType(type: String, payloadKey: String, payloadValue: String): Boolean
}
