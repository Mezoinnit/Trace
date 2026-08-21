package com.mezo.trace.infra.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Creates the PostgreSQL connection pool (HikariCP).
 *
 * PostgreSQL is the single source of truth. See ADR-003.
 */
object DatabaseFactory {

    fun createDataSource(config: DatabaseConfig = DatabaseConfig()): DataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            poolName = "trace-db"
            isAutoCommit = true
            // Explicit connection timeouts so degraded databases fail fast.
            connectionTimeout = 10_000
            validationTimeout = 3_000
            initializationFailTimeout = -1L
        }
        return HikariDataSource(hikari)
    }
}