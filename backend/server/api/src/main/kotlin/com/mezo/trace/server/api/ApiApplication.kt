package com.mezo.trace.server.api

import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.application.usecase.scheduler.IntervalCalculator
import com.mezo.trace.domain.UserId
import com.mezo.trace.domain.Version
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.infra.persistence.Migrator
import com.mezo.trace.infra.security.SecurityConfig
import com.mezo.trace.server.api.ratelimit.RateDecision
import com.mezo.trace.server.api.ratelimit.SlidingWindowRateLimiter
import io.ktor.http.ContentType
import kotlinx.coroutines.launch
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
import io.ktor.server.response.respondText
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

fun Application.module(
    container: ApiContainer,
    rateLimits: RateLimiters,
    secureHeaders: SecureHeadersConfig = SecureHeadersConfig().fromEnv(),
) {
    install(ContentNegotiation) {
        json()
    }
    install(SecureHeaders) {
        hstsEnabled = secureHeaders.hstsEnabled
        maxBodyBytes = secureHeaders.maxBodyBytes
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

        // Prometheus text exposition (Phase 14, OBSERVABILITY.md). Unbounded
        // cardinality is not possible: all counters use fixed tag values.
        get("/metrics") {
            val metrics = container.metrics as? com.mezo.trace.infra.metrics.MicrometerMetrics
            if (metrics == null) {
                call.respondText("# no metrics backend\n", ContentType.Text.Plain)
            } else {
                call.respondText(MetricsPrometheus.scrape(metrics.registry), ContentType.Text.Plain)
            }
        }

        apiV1Routes(container, rateLimits)
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataSource = DatabaseFactory.createDataSource()
    // Flyway on API startup (DEPLOYMENT.md §4) — required for single-process
    // deployments (EMBED_WORKER=true) where no standalone worker ever boots.
    Migrator.migrate(dataSource)
    val security = SecurityConfig.load()
    val container = ApiContainer(dataSource, security)

    // All-in-one deploy mode (free-tier hosting): when EMBED_WORKER=true the
    // API process also runs the async job loop and the monitoring scheduler
    // in-process. The standalone server/worker and server/scheduler units
    // remain the reference deployment (ADR-008/009) for horizontal scaling;
    // this flag exists so a single-user deployment fits one free instance.
    val embedWorker = System.getenv("EMBED_WORKER") == "true"

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(container, RateLimiters.fromEnv())
    }.start(wait = false)

    if (embedWorker) {
        startEmbeddedWorker(container)
    }

    // Park the main thread forever (worker loops run on coroutines).
    Thread.currentThread().join()
}

private val embeddedLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

private const val DEFAULT_SCHEDULER_TICK_MS = 5_000L
private const val EMBEDDED_IDLE_DELAY_MS = 1_000L

/** Runs JobLoop + ScheduleDueFetches forever, mirroring WorkerApplication/SchedulerApplication. */
private fun startEmbeddedWorker(container: ApiContainer) {
    val loop = JobLoop(
        jobs = container.jobRepository,
        tx = container.txRunner,
        clock = container.clock,
        concurrency = System.getenv("WORKER_CONCURRENCY")?.toIntOrNull() ?: JobLoop.DEFAULT_CONCURRENCY,
        fetchHandler = { endpointId ->
            when (val outcome = container.fetchEndpointNow.execute(endpointId)) {
                is com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success -> {
                    val intervalOutcome =
                        if (outcome.result.items.isNotEmpty()) IntervalCalculator.Outcome.ITEMS_FOUND
                        else IntervalCalculator.Outcome.SUCCESS_EMPTY
                    container.refineSchedule(endpointId, intervalOutcome)
                    intervalOutcome
                }

                is com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Failed ->
                    if (outcome.failureState == "RATE_LIMITED") IntervalCalculator.Outcome.RATE_LIMITED
                    else IntervalCalculator.Outcome.FAILURE
            }
        },
    )

    val scheduler = container.scheduler
    val schedulerTickMs = System.getenv("SCHEDULER_TICK_MS")?.toLongOrNull() ?: DEFAULT_SCHEDULER_TICK_MS

    embeddedLogger.info { "embedded worker+scheduler enabled (single-process deploy)" }

    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default +
            kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.CoroutineName("embedded-worker"),
    )
    scope.launch {
        while (true) {
            try {
                val result = loop.tick()
                if (result.claimed == 0) kotlinx.coroutines.delay(EMBEDDED_IDLE_DELAY_MS)
            } catch (t: Throwable) {
                embeddedLogger.error(t) { "embedded job loop tick failed" }
                kotlinx.coroutines.delay(EMBEDDED_IDLE_DELAY_MS)
            }
        }
    }
    scope.launch {
        while (true) {
            try {
                val summary = scheduler.execute()
                if (summary.enqueued > 0 || summary.initialized > 0) {
                    embeddedLogger.info { "scheduler pass: $summary" }
                }
            } catch (t: Throwable) {
                embeddedLogger.error(t) { "embedded scheduler pass failed" }
            }
            kotlinx.coroutines.delay(schedulerTickMs)
        }
    }
}
