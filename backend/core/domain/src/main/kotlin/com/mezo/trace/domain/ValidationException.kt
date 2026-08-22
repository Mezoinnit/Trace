package com.mezo.trace.domain

/**
 * Domain rule violation. Mapped to VALIDATION_ERROR at the HTTP edge.
 */
class ValidationException(
    val field: String?,
    message: String,
) : RuntimeException(message)
