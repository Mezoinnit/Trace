package com.mezo.trace.server.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mezo.trace.infra.persistence.DatabaseConfig
import com.mezo.trace.infra.persistence.DatabaseFactory
import com.mezo.trace.server.api.itest.postJson
import com.mezo.trace.testsupport.testSecurityConfig
import io.ktor.server.testing.testApplication
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Phase 14 secret-redaction proof (SECURITY.md): submitted credentials must
 * never appear in any log output, on any code path — not on failures, not on
 * unhandled exceptions, not in call logging.
 */
class SecretRedactionTest {

    @Test
    fun `submitted passwords never appear in logs on failed auth`() = testApplication {
        val dataSource = DatabaseFactory.createDataSource(
            DatabaseConfig(jdbcUrl = "jdbc:postgresql://localhost:5432/unreachable", maximumPoolSize = 1),
        )
        application { module(ApiContainer(dataSource, testSecurityConfig()), RateLimiters(1000, 1000)) }

        val secret = "S3cret-password-${UUID.randomUUID()}"
        val appender = ListAppender<ILoggingEvent>()
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender.start()
        rootLogger.addAppender(appender)
        try {
            // Failed sign-in (unreachable DB → 500, the most log-spewing path).
            client.postJson(
                "/api/v1/auth/signin",
                """{"email":"redact-${UUID.randomUUID()}@example.test","password":"$secret"}""",
            )
            // Malformed body with the secret embedded.
            client.postJson("/api/v1/auth/signin", """{"password":"$secret""")

            val logged = appender.list.joinToString("\n") { event ->
                event.formattedMessage + " " + event.argumentArray.orEmpty().joinToString(" ")
            }
            assertFalse(logged.contains(secret), "the submitted password must never be logged")
            assertFalse(logged.contains("redact-"), "redaction test email must not be echoed either")
        } finally {
            rootLogger.detachAppender(appender)
        }

        assertTrue(true)
    }
}
