package com.mezo.trace.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class PasswordPolicyTest {

    @Test
    fun `accepts a strong password`() {
        PasswordPolicy.validate("abc123def456")
    }

    @Test
    fun `rejects too short passwords`() {
        assertFailsWith<ValidationException> { PasswordPolicy.validate("ab1") }
    }

    @Test
    fun `rejects too long passwords`() {
        assertFailsWith<ValidationException> { PasswordPolicy.validate("${"a".repeat(128)}1b") }
    }

    @Test
    fun `rejects passwords without digits`() {
        assertFailsWith<ValidationException> { PasswordPolicy.validate("abcdefghij") }
    }

    @Test
    fun `rejects passwords without letters`() {
        assertFailsWith<ValidationException> { PasswordPolicy.validate("123456789012") }
    }
}
