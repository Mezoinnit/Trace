package com.mezo.trace.server.api.itest

import com.mezo.trace.domain.Email
import com.mezo.trace.domain.User
import com.mezo.trace.domain.UserId
import com.mezo.trace.infra.persistence.JdbcUserRepository
import com.mezo.trace.infra.persistence.TxRunnerImpl
import com.mezo.trace.testsupport.EmbeddedPg
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertFailsWith

/**
 * Transaction boundary contract (plan §24): repositories are only usable
 * inside an explicit transaction.
 */
class PersistenceContractTest {

    @Test
    fun `repository access outside a transaction fails fast`() {
        val tx = TxRunnerImpl(EmbeddedPg.dataSource())
        val users = JdbcUserRepository(tx)

        assertFailsWith<IllegalStateException> {
            users.findById(UserId(UUID.randomUUID().toString()))
        }
    }

    @Test
    fun `insert and lookup work inside a transaction`() {
        val tx = TxRunnerImpl(EmbeddedPg.dataSource())
        val users = JdbcUserRepository(tx)
        val id = UserId(UUID.randomUUID().toString())
        val email = Email.create("tx-${UUID.randomUUID()}@example.com")
        val user = User(id, email, null, "hash", Instant.now(), Instant.now())

        val found = tx.run {
            users.insert(user)
            users.findByEmail(email)
        }

        assertEquals(id, found?.id)
    }
}
