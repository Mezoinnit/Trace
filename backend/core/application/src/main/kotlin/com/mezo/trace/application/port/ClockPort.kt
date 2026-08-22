package com.mezo.trace.application.port

import java.time.Instant

/**
 * Clock port so time-dependent logic is testable.
 */
interface ClockPort {
    fun now(): Instant
}
