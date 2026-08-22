package com.mezo.trace.application

/**
 * Application-level errors mapped to the API.md error envelope at the HTTP
 * edge. Cross-tenant access is reported as NotFound to avoid resource
 * enumeration (API.md, ADR-010).
 */
sealed class AppError(
    val httpStatus: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message) {

    class ValidationFailed(val field: String?, message: String) :
        AppError(400, "VALIDATION_ERROR", message)

    class InvalidCredentials(message: String = "email or password is incorrect") :
        AppError(401, "UNAUTHENTICATED", message)

    class Unauthenticated(message: String = "authentication required") :
        AppError(401, "UNAUTHENTICATED", message)

    class Forbidden(message: String = "not allowed") :
        AppError(403, "FORBIDDEN", message)

    class NotFound(resource: String) :
        AppError(404, "NOT_FOUND", "$resource not found")

    class Conflict(message: String) :
        AppError(409, "CONFLICT", message)

    class RateLimited(val retryAfterSeconds: Long) :
        AppError(429, "RATE_LIMITED", "too many requests")
}
