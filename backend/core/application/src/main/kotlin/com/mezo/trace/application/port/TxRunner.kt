package com.mezo.trace.application.port

/**
 * Transaction boundary port.
 *
 * All repository calls must happen inside a transaction. Implementations
 * provide a connection bound to the current unit of work; nested calls join
 * the outer transaction.
 */
interface TxRunner {
    fun <T> run(block: () -> T): T
}
