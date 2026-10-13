package com.mezo.trace.server.api

import com.mezo.trace.application.AppError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

/**
 * Secure response headers and request body bounds (SECURITY.md §11, Phase 14).
 *
 * Applied to every response:
 * - `X-Content-Type-Options: nosniff`
 * - `X-Frame-Options: DENY`
 * - `Referrer-Policy: no-referrer`
 * - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
 * - `Strict-Transport-Security` only when the deployment terminates TLS
 *   ([SecureHeadersConfig.hstsEnabled]; off for plain-HTTP local dev).
 *
 * Applied to `/api/` responses:
 * - `Cache-Control: no-store` (private, tenant-scoped data must not be cached).
 *
 * Body bounds (Rule 6 — no unbounded operations): `/api/` requests with bodies
 * must declare `Content-Length` (411 otherwise) and it must not exceed
 * [SecureHeadersConfig.maxBodyBytes] (413 otherwise). The check runs before
 * any body is read, so oversized payloads are rejected without buffering.
 */
class SecureHeadersConfig {
    var hstsEnabled: Boolean = false
    var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    fun fromEnv(env: Map<String, String> = System.getenv()): SecureHeadersConfig = apply {
        env["TRACE_HSTS_ENABLED"]?.lowercase()?.let { hstsEnabled = it == "true" || it == "1" }
        env["TRACE_MAX_BODY_BYTES"]?.toLongOrNull()?.takeIf { it > 0 }?.let { maxBodyBytes = it }
    }

    companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 1L shl 20 // 1 MiB
    }
}

val SecureHeaders = createApplicationPlugin("TraceSecureHeaders", ::SecureHeadersConfig) {
    onCall { call ->
        val config = pluginConfig
        call.addSecureHeaders(config)
        bodyBoundDecision(
            path = call.request.path(),
            method = call.request.httpMethod,
            contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            limitBytes = config.maxBodyBytes,
        )?.let { throw it }
    }
}

private fun ApplicationCall.addSecureHeaders(config: SecureHeadersConfig) {
    val append: (String, String) -> Unit = { name, value ->
        response.headers.append(name, value, safeOnly = false)
    }
    append("X-Content-Type-Options", "nosniff")
    append("X-Frame-Options", "DENY")
    append("Referrer-Policy", "no-referrer")
    append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    if (config.hstsEnabled) {
        append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
    if (request.path().startsWith("/api/")) {
        append("Cache-Control", "no-store")
    }
}

private val BODY_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)

/** Pure decision so body-bound rules are unit-testable without HTTP. */
internal fun bodyBoundDecision(
    path: String,
    method: HttpMethod,
    contentLength: Long?,
    limitBytes: Long,
): AppError? {
    if (!path.startsWith("/api/")) return null
    if (method !in BODY_METHODS) return null
    return when {
        contentLength == null -> AppError.LengthRequired()
        contentLength > limitBytes -> AppError.PayloadTooLarge(limitBytes)
        else -> null
    }
}

