package com.mezo.trace.domain

/**
 * Email value object with normalization and format validation.
 *
 * Normalization: trim + lowercase. Storage uniqueness is case-insensitive
 * (see users_email_unique index).
 */
@JvmInline
value class Email(val value: String) {

    companion object {

        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /**
         * Normalizes and validates the raw input.
         *
         * @throws ValidationException when the email is not acceptable.
         */
        fun create(raw: String): Email {
            val normalized = raw.trim().lowercase()
            if (normalized.isEmpty()) {
                throw ValidationException("email", "email is required")
            }
            if (normalized.length > 320) {
                throw ValidationException("email", "email is too long")
            }
            if (!EMAIL_REGEX.matches(normalized)) {
                throw ValidationException("email", "email is not valid")
            }
            return Email(normalized)
        }
    }
}
