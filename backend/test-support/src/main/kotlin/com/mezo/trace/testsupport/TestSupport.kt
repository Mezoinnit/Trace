package com.mezo.trace.testsupport

import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.infra.security.SecurityConfig
import java.time.Duration
import java.time.Instant

/**
 * Advanceable clock for deterministic time-dependent tests.
 */
class TestClock(
    private var current: Instant = Instant.now(),
) : ClockPort {

    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }

    fun set(instant: Instant) {
        current = instant
    }
}

fun testSecurityConfig(): SecurityConfig = SecurityConfig(
    jwtSecret = ByteArray(32) { 0x42 },
    accessTokenTtl = Duration.ofMinutes(15),
    refreshTokenTtl = Duration.ofDays(30),
)
