package com.mezo.trace.testsupport

import com.mezo.trace.infra.persistence.Migrator
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * Shared embedded PostgreSQL instance for integration tests.
 *
 * Real Postgres binaries (no Docker required). Migrations are applied once
 * per JVM; tests isolate themselves with unique emails/ids.
 */
object EmbeddedPg {

    private val holder = AtomicReference<Holder?>()

    fun dataSource(): DataSource {
        holder.get()?.let { return it.dataSource }
        synchronized(this) {
            holder.get()?.let { return it.dataSource }
            val pg = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true)
                .start()
            val ds = pg.postgresDatabase
            Migrator.migrate(ds)
            Runtime.getRuntime().addShutdownHook(
                Thread { runCatching { pg.close() } },
            )
            val newHolder = Holder(ds)
            holder.set(newHolder)
            return newHolder.dataSource
        }
    }

    private class Holder(val dataSource: DataSource)
}
