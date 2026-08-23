package com.mezo.trace.domain

import java.time.Instant

/**
 * A durable asynchronous job (ADR-008, plan §22). Typed payloads keep
 * handlers self-describing; retries, backoff, dead-letter, and visibility
 * timeout are queue-level concerns.
 */
data class Job(
    val id: String,
    val type: String,
    val payload: Map<String, String>,
    val priority: Int = 0,
    val status: JobStatus,
    val attempts: Int = 0,
    val maxAttempts: Int = 5,
    val availableAt: Instant = Instant.EPOCH,
    val lockedAt: Instant? = null,
    val lockedBy: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val idempotencyKey: String? = null,
    val createdAt: Instant = Instant.EPOCH,
)

enum class JobStatus { QUEUED, LOCKED, COMPLETED, FAILED, DEAD }

object JobTypes {
    const val FETCH_SOURCE = "FETCH_SOURCE"
}
