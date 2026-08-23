package com.mezo.trace.application.usecase.scheduler

import java.security.SecureRandom
import java.time.Duration

/**
 * Adaptive polling intervals (ADR-009, plan §10).
 *
 * - fresh items → reset to minimum (source is active)
 * - success without items → lengthen geometrically toward maximum
 * - failures → exponential backoff on the failure count
 * - RATE_LIMITED → respect a fixed floor regardless of curve
 * - ±[jitterPercent] jitter prevents synchronized polling spikes
 */
object IntervalCalculator {

    data class Policy(
        val minInterval: Duration = Duration.ofMinutes(5),
        val maxInterval: Duration = Duration.ofMinutes(240),
        val baseInterval: Duration = Duration.ofMinutes(15),
        val jitterPercent: Int = 10,
        /** Fixed floor while a provider rate-limits us. */
        val rateLimitFloor: Duration = Duration.ofMinutes(30),
    )

    enum class Outcome { ITEMS_FOUND, SUCCESS_EMPTY, FAILURE, RATE_LIMITED }

    private val random = SecureRandom()

    fun nextInterval(
        outcome: Outcome,
        consecutiveFailures: Int,
        previousInterval: Duration?,
        policy: Policy = Policy(),
    ): Duration {
        val raw = when (outcome) {
            Outcome.ITEMS_FOUND -> policy.minInterval

            Outcome.SUCCESS_EMPTY -> {
                val start = when {
                    previousInterval == null || previousInterval < policy.minInterval ->
                        // First observation sits at base; growth starts after.
                        return applyJitter(coerce(policy.baseInterval, policy), policy.jitterPercent)

                    else -> previousInterval
                }
                lengthen(start, policy)
            }

            Outcome.FAILURE -> {
                val backoffBase = policy.minInterval
                var d = backoffBase
                repeat(consecutiveFailures.coerceAtMost(16)) { d = lengthen(d, policy) }
                d
            }

            Outcome.RATE_LIMITED -> policy.rateLimitFloor
        }

        return applyJitter(coerce(raw, policy), policy.jitterPercent)
    }

    private fun lengthen(current: Duration, policy: Policy): Duration =
        current.multipliedBy(2).let {
            if (it > policy.maxInterval) policy.maxInterval else it
        }

    private fun coerce(d: Duration, policy: Policy): Duration = when {
        d < policy.minInterval -> policy.minInterval
        d > policy.maxInterval -> policy.maxInterval
        else -> d
    }

    internal fun applyJitter(d: Duration, jitterPercent: Int): Duration {
        if (jitterPercent <= 0) return d
        val millis = d.toMillis()
        val spread = millis * jitterPercent / 100
        val offset = if (spread == 0L) 0 else (random.nextLong(spread * 2 + 1) - spread)
        return Duration.ofMillis((millis + offset).coerceAtLeast(1))
    }
}
