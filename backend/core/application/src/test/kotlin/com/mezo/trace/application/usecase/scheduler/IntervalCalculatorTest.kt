package com.mezo.trace.application.usecase.scheduler

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntervalCalculatorTest {

    private val policy = IntervalCalculator.Policy(
        minInterval = Duration.ofMinutes(5),
        maxInterval = Duration.ofMinutes(240),
        baseInterval = Duration.ofMinutes(15),
        jitterPercent = 0, // deterministic for the matrix assertions
    )

    @Test
    fun `items found resets to minimum`() {
        assertEquals(
            Duration.ofMinutes(5),
            IntervalCalculator.nextInterval(
                IntervalCalculator.Outcome.ITEMS_FOUND,
                consecutiveFailures = 0,
                previousInterval = Duration.ofMinutes(120),
                policy = policy,
            ),
        )
    }

    @Test
    fun `success without items lengthens geometrically from base`() {
        val first = IntervalCalculator.nextInterval(
            IntervalCalculator.Outcome.SUCCESS_EMPTY,
            consecutiveFailures = 0,
            previousInterval = null,
            policy = policy,
        )
        assertEquals(Duration.ofMinutes(15), first)

        val second = IntervalCalculator.nextInterval(
            IntervalCalculator.Outcome.SUCCESS_EMPTY,
            consecutiveFailures = 0,
            previousInterval = first,
            policy = policy,
        )
        assertEquals(Duration.ofMinutes(30), second)

        // Caps at maximum.
        val capped = IntervalCalculator.nextInterval(
            IntervalCalculator.Outcome.SUCCESS_EMPTY,
            consecutiveFailures = 0,
            previousInterval = Duration.ofMinutes(480),
            policy = policy,
        )
        assertEquals(Duration.ofMinutes(240), capped)
    }

    @Test
    fun `failures back off exponentially with the streak`() {
        fun forFailures(n: Int) = IntervalCalculator.nextInterval(
            IntervalCalculator.Outcome.FAILURE, n, null, policy,
        )

        val one = forFailures(1)
        val two = forFailures(2)
        val four = forFailures(4)

        assertTrue(two > one, "expected $two > $one")
        assertTrue(four > two, "expected $four > $two")
        assertTrue(four <= Duration.ofMinutes(240))
    }

    @Test
    fun `rate limiting enforces its floor`() {
        assertEquals(
            Duration.ofMinutes(30),
            IntervalCalculator.nextInterval(
                IntervalCalculator.Outcome.RATE_LIMITED,
                consecutiveFailures = 3,
                previousInterval = Duration.ofMinutes(5),
                policy = policy,
            ),
        )
    }

    @Test
    fun `jitter stays within the configured band`() {
        val d = Duration.ofMinutes(60)
        repeat(200) {
            val j = IntervalCalculator.applyJitter(d, jitterPercent = 10)
            val lo = d.toMillis() * 90 / 100
            val hi = d.toMillis() * 110 / 100 + 1
            assertTrue(j.toMillis() >= lo && j.toMillis() <= hi, "jitter out of band: $j")
        }
    }
}
