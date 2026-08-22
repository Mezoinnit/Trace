package com.mezo.trace.server.api.ratelimit

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class SlidingWindowRateLimiterTest {

    private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `allows up to the limit then denies with retry-after`() {
        val limiter = SlidingWindowRateLimiter(limit = 2, window = Duration.ofSeconds(10)) { now }

        assertEquals(RateDecision.Allowed, limiter.allow("k"))
        assertEquals(RateDecision.Allowed, limiter.allow("k"))

        val denied = limiter.allow("k")
        assertTrue(denied is RateDecision.Denied)
        assertTrue((denied as RateDecision.Denied).retryAfterSeconds >= 1)
    }

    @Test
    fun `window slides so requests are allowed again`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofSeconds(10)) { now }

        assertEquals(RateDecision.Allowed, limiter.allow("k"))
        assertTrue(limiter.allow("k") is RateDecision.Denied)

        now = now.plus(Duration.ofSeconds(11))

        assertEquals(RateDecision.Allowed, limiter.allow("k"))
    }

    @Test
    fun `keys are isolated`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofSeconds(10)) { now }

        assertEquals(RateDecision.Allowed, limiter.allow("a"))
        assertEquals(RateDecision.Allowed, limiter.allow("b"))
        assertTrue(limiter.allow("a") is RateDecision.Denied)
    }

    @Test
    fun `sweep keeps tracked keys bounded`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofSeconds(10)) { now }
        repeat(50) { limiter.allow("key-$it") }

        now = now.plus(Duration.ofSeconds(61))
        assertEquals(RateDecision.Allowed, limiter.allow("fresh"))

        assertEquals(1, limiter.trackedKeyCount())
    }
}
