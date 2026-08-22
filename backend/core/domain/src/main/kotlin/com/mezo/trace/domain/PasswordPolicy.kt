package com.mezo.trace.domain

/**
 * Password strength policy. Pure validation only — hashing is infrastructure.
 */
object PasswordPolicy {

    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 128

    fun validate(password: String) {
        if (password.length < MIN_LENGTH) {
            throw ValidationException(
                "password",
                "password must be at least $MIN_LENGTH characters",
            )
        }
        if (password.length > MAX_LENGTH) {
            throw ValidationException(
                "password",
                "password must be at most $MAX_LENGTH characters",
            )
        }
        if (password.none { it.isLetter() } || password.none { it.isDigit() }) {
            throw ValidationException(
                "password",
                "password must contain letters and digits",
            )
        }
    }
}
