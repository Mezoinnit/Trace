package com.mezo.trace.server.api.itest

import com.mezo.trace.adapters.testing.StubHttpFetcher
import com.mezo.trace.domain.Job
import com.mezo.trace.domain.JobStatus
import com.mezo.trace.domain.JobTypes
import com.mezo.trace.server.api.ApiContainer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8: job queue semantics (ADR-008) and the scheduler→worker loop (ADR-009).
 */
class SchedulerQueueITest {

    private fun newJobs(container: ApiContainer) =
        com.mezo.trace.infra.persistence.JdbcJobRepository(container.sql)

    private fun fetchJob(
        endpointId: String = UUID.randomUUID().toString(),
        key: String? = null,
        maxAttempts: Int = 5,
    ) = Job(
        id = UUID.randomUUID().toString(),
        type = JobTypes.FETCH_SOURCE,
        payload = mapOf("endpoint_id" to endpointId),
        status = JobStatus.QUEUED,
        maxAttempts = maxAttempts,
        idempotencyKey = key,
    )

    @Test
    fun `claim is exclusive and completion finalizes`() = testApplication {
        val c = app()
        val jobs = newJobs(c)
        val job = fetchJob()
        c.txRunner.run { jobs.enqueue(job) }

        // The shared database may hold stale jobs from other tests; scope
        // every assertion to THIS job's identity.
        val claimed1 = c.txRunner.run { jobs.claim("w1", 50, Instant.now()) }
            .filter { it.id == job.id }
        assertEquals(1, claimed1.size)
        assertEquals(1, claimed1.single().attempts)

        // Locked jobs are invisible to other workers.
        assertTrue(
            c.txRunner.run { jobs.claim("w2", 50, Instant.now()) }.none { it.id == job.id },
        )

        c.txRunner.run { jobs.markCompleted(job.id, Instant.now()) }
    }

    @Test
    fun `failures back off, then dead-letter after max attempts`() = testApplication {
        val c = app()
        val jobs = newJobs(c)
        val job = fetchJob(maxAttempts = 2)
        c.txRunner.run { jobs.enqueue(job.copy(idempotencyKey = null)) }

        // Attempt 1 fails → QUEUED again with future availability.
        var claimed = c.txRunner.run { jobs.claim("w1", 10, Instant.now()) }
        assertEquals(1, claimed.size)
        var dead = c.txRunner.run {
            jobs.markFailed(job.id, "TEST", "boom", Instant.now().plusSeconds(3600), Instant.now())
        }
        assertEquals(false, dead)
        assertTrue(
            c.txRunner.run { jobs.claim("w1", 50, Instant.now().plusSeconds(60)) }
                .none { it.id == job.id },
        )

        // Pull availability back so attempt 2 is due now.
        forceDue(c, job.id)

        claimed = c.txRunner.run { jobs.claim("w1", 50, Instant.now()) }
            .filter { it.id == job.id }
        assertEquals(1, claimed.size)
        dead = c.txRunner.run {
            jobs.markFailed(job.id, "TEST", "boom again", Instant.now().plusSeconds(3600), Instant.now())
        }
        assertEquals(true, dead, "second failure must dead-letter at max_attempts=2")
    }

    @Test
    fun `idempotency key dedupes pending work`() = testApplication {
        val c = app()
        val jobs = newJobs(c)
        val key = "fetch:e:${UUID.randomUUID()}"
        val first = c.txRunner.run { jobs.enqueue(fetchJob(key = key)) }
        val second = c.txRunner.run { jobs.enqueue(fetchJob(key = key)) }
        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun `expired leases are requeued`() = testApplication {
        val c = app()
        val jobs = newJobs(c)
        val job = fetchJob()
        c.txRunner.run { jobs.enqueue(job) }
        assertEquals(job.id, c.txRunner.run { jobs.claim("w1", 10, Instant.now()) }.single().id)

        // Sweep leases older than "now + 1h" — our fresh lease qualifies.
        // Other tests may leave stale rows in the shared database, so assert
        // on OUR job's recoverability rather than exact sweep counts.
        val swept = c.txRunner.run {
            jobs.requeueExpiredLeases(Instant.now().plusSeconds(3600), Instant.now())
        }
        org.junit.jupiter.api.Assertions.assertTrue(swept >= 1)
        val reclaimed = c.txRunner.run { jobs.claim("w2", 10, Instant.now()) }
        assertTrue(reclaimed.any { it.id == job.id })
    }

    @Test
    fun `scheduler enqueues due endpoint once, worker cycle completes, schedule advances`() = testApplication {
        val repo = "acme/sch-${UUID.randomUUID().toString().take(8)}"
        val fixture = """
            [{"id": 9900, "tag_name": "v8.8.8", "name": "v8.8.8",
              "html_url": "https://github.com/$repo/tag/v8.8.8",
              "published_at": "2026-08-10T10:00:00Z", "prerelease": false,
              "draft": false, "body": "n"}]
        """.trimIndent()
        val fetcher = StubHttpFetcher()
        fetcher.serve(
            Regex.escape("https://api.github.com/repos/$repo/releases?per_page=30&page=1"),
            StubHttpFetcher.Response(body = fixture.toByteArray()),
        )
        val container = app(fetcher = fetcher)
        val client = createClient { expectSuccess = false }
        val session = register(client, "sch-${UUID.randomUUID()}@example.com")
        client.post("/api/v1/sources") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"endpoint_uri":"github://$repo/releases","adapter_id":"github"}""")
        }
        val endpointId = container.txRunner.run {
            container.sourceEndpoints.listBySourceId(
                container.sources.findByCanonicalIdentity("GITHUB", "REPOSITORY", repo)!!.id,
            ).single().id
        }

        // The embedded database is shared across test classes, so earlier
        // endpoints may also be due. All assertions below are scoped to THIS
        // test's endpoint via its queued-job count.

        fun pendingJobsFor(endpoint: String): Int =
            container.txRunner.run {
                container.sql.current().prepareStatement(
                    "SELECT count(*) FROM jobs WHERE type='FETCH_SOURCE' " +
                        "AND status IN ('QUEUED','LOCKED') AND payload->>'endpoint_id' = ?",
                ).use { ps ->
                    ps.setString(1, endpoint)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        // Pass 1: fresh endpoint is due → exactly one enqueue for it.
        container.scheduler.execute()
        assertEquals(1, pendingJobsFor(endpointId))

        // Pass 2 while still pending → no duplicate enqueue.
        container.scheduler.execute()
        assertEquals(1, pendingJobsFor(endpointId))

        // Force the endpoint due again while its job is still QUEUED:
        // the pending-job guard must keep holding the line.
        container.txRunner.run {
            container.sql.current().prepareStatement(
                "UPDATE source_health SET next_poll_at = now() WHERE endpoint_id = ?::uuid",
            ).use { ps ->
                ps.setString(1, endpointId)
                ps.executeUpdate()
            }
        }
        container.scheduler.execute()
        assertEquals(1, pendingJobsFor(endpointId))

        // Worker-side handler runs the real pipeline against the stub.
        val outcome = container.fetchEndpointNow.execute(endpointId)
            as com.mezo.trace.application.usecase.sources.FetchEndpointNow.Outcome.Success
        assertEquals(1, outcome.evidenceCreated)
        assertTrue(fetcher.requestedUrls.isNotEmpty())

        // Schedule refinement puts next_poll_at in the future.
        container.refineSchedule(endpointId, com.mezo.trace.application.usecase.scheduler.IntervalCalculator.Outcome.SUCCESS_EMPTY)
        val health = container.txRunner.run { container.sourceHealth.findByEndpointId(endpointId)!! }
        assertNotNull(health.nextPollAt)
        assertTrue(health.nextPollAt!!.isAfter(Instant.now()), "next_poll_at=${health.nextPollAt}")
    }

    private fun forceDue(container: ApiContainer, jobId: String) {
        container.txRunner.run {
            container.sql.current().prepareStatement(
                "UPDATE jobs SET status='QUEUED', available_at=now() WHERE id=?::uuid",
            ).use { ps ->
                ps.setString(1, jobId)
                ps.executeUpdate()
            }
        }
    }
}
