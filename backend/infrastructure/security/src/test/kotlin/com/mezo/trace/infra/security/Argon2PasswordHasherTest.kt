package com.mezo.trace.infra.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class Argon2PasswordHasherTest {

    private val hasher = Argon2PasswordHasher(memoryKiB = 8192, iterations = 1)

    @Test
    fun `hash verifies correct password`() {
        val encoded = hasher.hash("correct-horse-1")
        assertTrue(hasher.verify("correct-horse-1", encoded))
    }

    @Test
    fun `wrong password fails verification`() {
        val encoded = hasher.hash("correct-horse-1")
        assertFalse(hasher.verify("incorrect-horse-1", encoded))
    }

    @Test
    fun `salts are unique so hashes differ`() {
        val a = hasher.hash("same-password-1")
        val b = hasher.hash("same-password-1")
        assertTrue(a != b)
        assertTrue(hasher.verify("same-password-1", a))
        assertTrue(hasher.verify("same-password-1", b))
    }

    @Test
    fun `malformed encoded strings fail safely`() {
        assertFalse(hasher.verify("x", ""))
        assertFalse(hasher.verify("x", "not-a-hash"))
        assertFalse(hasher.verify("x", "\$argon2id\$v=19\$m=1,t=1,p=1\$\$\$"))
        assertFalse(hasher.verify("x", "\$bcrypt\$whatever"))
    }
}
