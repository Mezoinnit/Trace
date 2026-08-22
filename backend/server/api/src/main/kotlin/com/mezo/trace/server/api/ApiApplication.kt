package com.mezo.trace.server.api

import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.domain.UserId
import com.mezo.trace.domain.Version
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.infra.security.SecurityConfig
import com.mezo.trace.server.api.ratelimit.RateDecision
import com.mezo.trace.server.api.ratelimit.SlidingWindowRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Duration
import java.util.UUID

/**
 * Per-endpoint-class limiters. Auth routes are keyed by client IP; general
 * API routes by user id (falling back to IP before authentication).
 */
class RateLimiters(
    authPerMinute: Int,
    apiPerMinute: Int,
) {
    val auth = SlidingWindowRateLimiter(authPerMinute, Duration.ofMinutes(1))
    val api = SlidingWindowRateLimiter(apiPerMinute, Duration.ofMinutes(1))

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = RateLimiters(
            authPerMinute = env["RATE_LIMIT_AUTH_PER_MIN"]?.toIntOrNull() ?: 10,
            apiPerMinute = env["RATE_LIMIT_API_PER_MIN"]?.toIntOrNull() ?: 120,
        )
    }
}

private fun ApplicationCall.clientKey(tokenIssuer: TokenIssuer): String {
    val ip = request.origin.remoteHost ?: "unknown"
    val bearer = request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.removePrefix("Bearer")?.trim()
    val userId: UserId? = bearer?.let { runCatching { tokenIssuer.validateAccessToken(it) }.getOrNull() }
    return if (userId != null) "u:${userId.value}" else "ip:$ip"
}

fun Application.module(container: ApiContainer, rateLimits: RateLimiters) {
    install(ContentNegotiation) {
        json()
    }
    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate { UUID.randomUUID().toString() }
    }
    install(CallLogging) {
        mdc("request_id") { c -> c.callId ?: "" }
    }
    install(StatusPages) {
        apiErrorEnvelope()
    }

    // Rate limiting runs before routing; auth endpoints are protected harder.
    intercept(ApplicationCallPipeline.Plugins) {
        val call = context
        val path = call.request.path()
        val decision = when {
            path.startsWith("/api/v1/auth/") ->
                rateLimits.auth.allow("ip:${call.request.origin.remoteHost}")
            path.startsWith("/api/v1/") ->
                rateLimits.api.allow(call.clientKey(container.tokenIssuer))
            else -> RateDecision.Allowed
        }
        if (decision is RateDecision.Denied) {
            throw com.mezo.trace.application.AppError.RateLimited(decision.retryAfterSeconds)
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(status = "ok", service = "api", version = Version.VERSION),
            )
        }

        get("/health/ready") {
            val databaseReady = container.databaseReady()
            val status = if (databaseReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(
                status,
                ReadinessResponse(
                    status = if (databaseReady) "ready" else "degraded",
                    database = if (databaseReady) "up" else "down",
                ),
            )
        }

        apiV1Routes(container, rateLimits)
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataSource = DatabaseFactory.createDataSource()
    val security = SecurityConfig.load()
    val container = ApiContainer(dataSource, security)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(container, RateLimiters.fromEnv())
    }.start(wait = true)
}
