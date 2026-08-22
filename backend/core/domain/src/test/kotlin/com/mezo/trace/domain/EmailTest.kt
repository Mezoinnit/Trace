package com.mezo.trace.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertFailsWith

class EmailTest {

    @Test
    fun `normalizes trim and lowercase`() {
        assertEquals("user@example.com", Email.create("  User@Example.COM ").value)
    }

    @Test
    fun `rejects empty`() {
        assertFailsWith<ValidationException> { Email.create("   ") }
    }

    @Test
    fun `rejects missing at sign`() {
        assertFailsWith<ValidationException> { Email.create("not-an-email") }
    }

    @Test
    fun `rejects missing domain dot`() {
        assertFailsWith<ValidationException> { Email.create("user@example") }
    }

    @Test
    fun `rejects overly long emails`() {
        assertFailsWith<ValidationException> { Email.create("${"a".repeat(320)}@example.com") }
    }
}
