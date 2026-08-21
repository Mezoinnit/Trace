package com.mezo.trace.infra.persistence

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Applies versioned schema migrations.
 *
 * Strategy: versioned SQL migrations under `db/migration`, never destructive
 * (`fallbackToDestructiveMigration` is forbidden). See ADR-003 and the
 * migrations strategy in DEPLOYMENT.md.
 */
object Migrator {

    fun migrate(dataSource: DataSource): Unit {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .load()
            .migrate()
    }
}