package com.mezo.trace.server.worker

import com.mezo.trace.application.usecase.scheduler.IntervalCalculator
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.infra.persistence.JdbcJobRepository
import com.mezo.trace.infra.persistence.Migrator
import com.mezo.trace.server.api.ApiContainer
import com.mezo.trace.server.api.JobLoop
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Async job worker deployable unit (ADR-008).
 *
 * `--migrate-only` applies migrations and exits (used by CI).
 */
fun main(args: Array<String>) = runBlocking {
    val dataSource = DatabaseFactory.createDataSource()
    Migrator.migrate(dataSource)

    if (args.contains("--migrate-only")) {
        logger.info { "migrations applied; exiting" }
        return@runBlocking
    }

    val security = com.mezo.trace.infra.security.SecurityConfig.load()
    val container = ApiContainer(dataSource, security)
    val jobs = JdbcJobRepository(container.sql)

    val loop = JobLoop(
        jobs = jobs,
        tx = container.txRunner,
        clock = container.clock,
        concurrency = System.getenv("WORKER_CONCURRENCY")?.toIntOrNull() ?: JobLoop.DEFAULT_CONCURRENCY,
        fetchHandler = { endpointId ->
            when (val outcome = container.fetchEndpointNow.execute(endpointId)) {
                is com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success -> {
                    // Refine next_poll_at from the real outcome (ADR-009).
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

    logger.info { "worker ready: $loop" }
    while (true) {
        val result = loop.tick()
        if (result.claimed == 0) delay(1000)
    }
}
