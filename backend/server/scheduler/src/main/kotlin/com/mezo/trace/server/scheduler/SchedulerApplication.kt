package com.mezo.trace.server.scheduler

import com.mezo.trace.application.usecase.scheduler.ScheduleDueFetches
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.infra.persistence.JdbcJobRepository
import com.mezo.trace.infra.persistence.JdbcSourceEndpointRepository
import com.mezo.trace.infra.persistence.JdbcSourceHealthRepository
import com.mezo.trace.infra.persistence.Migrator
import com.mezo.trace.infra.security.SecurityConfig
import com.mezo.trace.server.api.ApiContainer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Adaptive monitoring scheduler deployable unit (ADR-009).
 *
 * Every tick: scan due endpoints, enqueue bounded FETCH_SOURCE jobs, advance
 * schedules with jittered adaptive intervals.
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

    val security = SecurityConfig.load()
    val container = ApiContainer(dataSource, security)
    val scheduler = ScheduleDueFetches(
        endpoints = JdbcSourceEndpointRepository(container.sql),
        health = JdbcSourceHealthRepository(container.sql),
        jobs = JdbcJobRepository(container.sql),
        tx = container.txRunner,
        clock = container.clock,
    )

    val passEveryMs = System.getenv("SCHEDULER_TICK_MS")?.toLongOrNull() ?: 5_000L
    logger.info { "scheduler ready; ticking every ${passEveryMs}ms" }

    while (true) {
        try {
            val summary = scheduler.execute()
            if (summary.enqueued > 0 || summary.initialized > 0) {
                logger.info { "scheduler pass: $summary" }
            }
        } catch (t: Throwable) {
            logger.error(t) { "scheduler pass failed" }
        }
        delay(passEveryMs)
    }
}
