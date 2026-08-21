package com.mezo.trace.server.scheduler

import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.infra.persistence.Migrator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Adaptive monitoring scheduler deployable unit.
 *
 * Computes `next_poll_at` for every source endpoint with jitter and enqueues
 * FETCH_SOURCE jobs (ADR-009). Wired in Phase 8. In this scaffold the
 * scheduler starts, applies migrations, and waits.
 */
fun main() = runBlocking {
    val dataSource = DatabaseFactory.createDataSource()
    Migrator.migrate(dataSource)

    logger.info { "scheduler ready" }

    Thread.currentThread().join()
}