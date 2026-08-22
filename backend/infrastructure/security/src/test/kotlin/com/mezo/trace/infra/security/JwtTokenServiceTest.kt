package com.mezo.trace.infra.security

import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.domain.UserId
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

private class TestClock(private var current: Instant = Instant.now()) : ClockPort {
    override fun now(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

class JwtTokenServiceTest {

    private val clock = TestClock()
    private val service = JwtTokenService(ByteArray(32) { 1 }, clock, Duration.ofMinutes(15))

    @Test
    fun `issued token validates back to its subject`() {
        val userId = UserId("11111111-1111-1111-1111-111111111111")
        val issued = service.issueAccessToken(userId)

        assertEquals(userId, service.validateAccessToken(issued.token))
    }

    @Test
    fun `expired tokens are rejected`() {
        val issued = service.issueAccessToken(UserId("22222222-2222-2222-2222-222222222222"))

        clock.advance(Duration.ofMinutes(16))

        assertNull(service.validateAccessToken(issued.token))
    }

    @Test
    fun `tampered tokens are rejected`() {
        val issued = service.issueAccessToken(UserId("33333333-3333-3333-3333-333333333333"))
        val tampered = issued.token.dropLast(2) + "xx"

        assertNull(service.validateAccessToken(tampered))
    }

    @Test
    fun `refresh tokens are random and hashed deterministically`() {
        val a = service.generateRefreshToken()
        val b = service.generateRefreshToken()

        assertNotEquals(a, b)
        assertEquals(service.hash(a), service.hash(a))
        assertNotEquals(service.hash(a), service.hash(b))
        assertTrue(service.hash(a).length == 64) // sha-256 hex
    }

    @Test
    fun `short secrets are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            JwtTokenService(ByteArray(8), clock, Duration.ofMinutes(15))
        }
    }
}
