package com.mezo.trace.server.api.ratelimit

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface RateDecision {
    data object Allowed : RateDecision
    data class Denied(val retryAfterSeconds: Long) : RateDecision
}

/**
 * Sliding-window rate limiter with bounded memory (Rule 6).
 *
 * Single-instance only; multi-instance deployments will need a shared store
 * (documented in SECURITY.md §5). Redis-style infrastructure is forbidden by
 * the plan until scale justifies it.
 */
class SlidingWindowRateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: () -> Instant = Instant::now,
) {

    private val buckets = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    private var lastSweepAt: Instant = clock()

    fun allow(key: String): RateDecision {
        val now = clock()
        sweepIfNeeded(now)

        val deque = buckets.computeIfAbsent(key) { ArrayDeque() }
        synchronized(deque) {
            evictExpired(deque, now)
            if (deque.size >= limit) {
                val oldest = deque.first()
                val retryAfter = Duration.between(now, oldest.plus(window)).seconds
                    .coerceAtLeast(1L)
                return RateDecision.Denied(retryAfter)
            }
            deque.addLast(now)
            return RateDecision.Allowed
        }
    }

    /** Visible for tests. */
    fun trackedKeyCount(): Int = buckets.size

    private fun evictExpired(deque: ArrayDeque<Instant>, now: Instant) {
        while (deque.isNotEmpty() && !deque.first().plus(window).isAfter(now)) {
            deque.removeFirst()
        }
    }

    private fun sweepIfNeeded(now: Instant) {
        val sweepDue = now.isAfter(lastSweepAt.plus(SWEEP_INTERVAL))
        if (!sweepDue && buckets.size <= MAX_KEYS) return
        lastSweepAt = now
        buckets.entries.removeIf { entry ->
            synchronized(entry.value) {
                entry.value.removeAll { instant -> !instant.plus(window).isAfter(now) }
                entry.value.isEmpty()
            }
        }
    }

    private companion object {
        const val MAX_KEYS = 100_000
        val SWEEP_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
