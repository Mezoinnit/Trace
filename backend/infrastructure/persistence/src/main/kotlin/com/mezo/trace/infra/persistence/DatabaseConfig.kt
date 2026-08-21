package com.mezo.trace.infra.persistence

/**
 * Database connection configuration.
 *
 * All values are read from environment variables so that local development,
 * CI, and production use the same mechanism. No secrets are committed.
 *
 * Defaults target the local docker-compose Postgres instance.
 */
data class DatabaseConfig(
    val jdbcUrl: String = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/trace",
    val username: String = System.getenv("DATABASE_USER") ?: "trace",
    val password: String = System.getenv("DATABASE_PASSWORD") ?: "trace",
    val maximumPoolSize: Int = System.getenv("DATABASE_MAX_POOL_SIZE")?.toIntOrNull() ?: 10,
)