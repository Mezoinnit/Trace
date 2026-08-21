package com.mezo.trace.server.worker

import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.infra.persistence.Migrator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Async job worker deployable unit.
 *
 * Consumes durable jobs from the PostgreSQL-backed job queue (ADR-008).
 * Domain jobs are wired in Phase 1+. In this scaffold the worker starts,
 * applies migrations, and waits for work.
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

    logger.info { "worker ready; waiting for jobs" }

    // Block until the process is asked to stop; workers are expected to be
    // long-lived and supervised by the deployment platform.
    Thread.currentThread().join()
}