package com.mezo.trace.server.api

import com.mezo.trace.application.AppError
import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.domain.UserId
import io.ktor.server.application.ApplicationCall

/**
 * Resolves the authenticated principal from the Bearer access token.
 * Tenant context is derived from this identity only — never from request
 * bodies (ADR-010).
 */
fun ApplicationCall.requireUserId(tokenIssuer: TokenIssuer): UserId {
    val header = request.headers["Authorization"] ?: throw AppError.Unauthenticated()
    if (!header.startsWith("Bearer ", ignoreCase = true)) throw AppError.Unauthenticated()
    val token = header.removePrefix("Bearer ").trim()
    if (token.isEmpty()) throw AppError.Unauthenticated()
    return tokenIssuer.validateAccessToken(token) ?: throw AppError.Unauthenticated("invalid or expired access token")
}
