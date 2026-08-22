package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.TxRunner
import java.sql.Connection
import javax.sql.DataSource

/**
 * Connection accessor for repositories. Connections are only available inside
 * a transaction — this enforces explicit transaction boundaries everywhere
 * (plan §24).
 */
interface SqlConnections {
    fun current(): Connection
}

class TxRunnerImpl(private val dataSource: DataSource) : TxRunner, SqlConnections {

    private val connectionHolder = ThreadLocal<Connection?>()

    override fun current(): Connection {
        return connectionHolder.get()
            ?: throw IllegalStateException(
                "no transaction active; repository calls must run inside TxRunner.run",
            )
    }

    override fun <T> run(block: () -> T): T {
        // Nested invocations join the outer transaction.
        if (connectionHolder.get() != null) return block()

        val connection = dataSource.connection
        connection.autoCommit = false
        connectionHolder.set(connection)
        try {
            val result = block()
            connection.commit()
            return result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connectionHolder.remove()
            runCatching {
                connection.autoCommit = true
                connection.close()
            }
        }
    }
}
