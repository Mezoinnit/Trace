package com.mezo.trace.server.api

import com.mezo.trace.application.AppError
import com.mezo.trace.domain.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String?,
    val details: List<FieldErrorDto> = emptyList(),
)

@Serializable
data class FieldErrorDto(val field: String, val reason: String)

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

private suspend fun ApplicationCall.envelope(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: List<FieldErrorDto> = emptyList(),
) = respond(status, ErrorEnvelope(ErrorBody(code, message, callId, details)))

fun StatusPagesConfig.apiErrorEnvelope() {
    // Malformed request bodies (bad JSON, missing required fields, wrong
    // types) surface as 400 instead of an unhandled 500 — a Phase 14
    // hardening fix surfaced by the cross-tenant test matrix.
    exception<BadRequestException> { call, cause ->
        call.application.log.warn("bad request on ${call.request.path()}: ${cause.message}")
        call.envelope(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "request body is invalid")
    }

    exception<AppError> { call, cause ->
        if (cause is AppError.RateLimited) {
            call.response.headers.append("Retry-After", cause.retryAfterSeconds.toString())
        }
        call.envelope(HttpStatusCode.fromValue(cause.httpStatus), cause.code, cause.message)
    }

    exception<ValidationException> { call, cause ->
        val details = cause.field?.let { listOf(FieldErrorDto(it, cause.message ?: "invalid")) } ?: emptyList()
        call.envelope(HttpStatusCode.BadRequest, "VALIDATION_ERROR", cause.message ?: "validation failed", details)
    }

    exception<Throwable> { call, cause ->
        call.application.log.error("unhandled error on ${call.request.path()}", cause)
        call.envelope(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "internal error")
    }

    status(HttpStatusCode.NotFound) { call, _ ->
        call.envelope(HttpStatusCode.NotFound, "NOT_FOUND", "resource not found")
    }

    status(HttpStatusCode.MethodNotAllowed) { call, _ ->
        call.envelope(HttpStatusCode.MethodNotAllowed, "NOT_FOUND", "resource not found")
    }
}
