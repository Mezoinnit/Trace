package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.JobRepository
import com.mezo.trace.domain.Job
import com.mezo.trace.domain.JobStatus
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class JdbcJobRepository(private val sql: SqlConnections) : JobRepository {

    override fun enqueue(job: Job): Job? {
        val inserted = sql.current().prepareStatement(
            """
            INSERT INTO jobs (id, type, payload, priority, status, attempts, max_attempts,
                              available_at, idempotency_key, created_at)
            VALUES (?, ?, ?::jsonb, ?, 'QUEUED', 0, ?, ?, ?, now())
            ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(job.id))
            ps.setString(2, job.type)
            ps.setString(3, objJson(job.payload))
            ps.setInt(4, job.priority)
            ps.setInt(5, job.maxAttempts)
            ps.setInstant(6, job.availableAt.let { if (it == Instant.EPOCH) Instant.now() else it })
            ps.setNullableString(7, job.idempotencyKey)
            ps.executeUpdate() > 0
        }
        return if (inserted) job.copy(status = JobStatus.QUEUED) else null
    }

    override fun claim(workerId: String, limit: Int, now: Instant): List<Job> =
        sql.current().prepareStatement(
            """
            WITH due AS (
                SELECT id FROM jobs
                WHERE status = 'QUEUED' AND available_at <= ?
                ORDER BY priority DESC, available_at, created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE jobs j
            SET status = 'LOCKED', locked_at = ?, locked_by = ?, attempts = attempts + 1
            FROM due
            WHERE j.id = due.id
            RETURNING j.*
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, now)
            ps.setInt(2, limit)
            ps.setInstant(3, now)
            ps.setString(4, workerId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Job>()
                while (rs.next()) out += rs.toJob()
                out
            }
        }

    override fun markCompleted(jobId: String, now: Instant) {
        sql.current().prepareStatement(
            "UPDATE jobs SET status = 'COMPLETED', completed_at = ?, locked_by = NULL WHERE id = ?",
        ).use { ps ->
            ps.setInstant(1, now)
            ps.setObject(2, UUID.fromString(jobId))
            ps.executeUpdate()
        }
    }

    override fun markFailed(
        jobId: String,
        errorCode: String?,
        errorMessage: String?,
        availableAt: Instant,
        now: Instant,
    ): Boolean {
        val dead = exhausted(jobId)
        val nextStatus = if (dead) "DEAD" else "QUEUED"
        sql.current().prepareStatement(
            """
            UPDATE jobs SET status = ?, error_code = ?, error_message = ?,
                             failed_at = ?, available_at = ?, locked_at = NULL, locked_by = NULL
            WHERE id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, nextStatus)
            ps.setNullableString(2, errorCode)
            ps.setNullableString(3, errorMessage?.take(500))
            ps.setInstant(4, now)
            ps.setInstant(5, if (dead) now else availableAt)
            ps.setObject(6, UUID.fromString(jobId))
            ps.executeUpdate()
        }
        return dead
    }

    override fun exhausted(jobId: String): Boolean {
        val uuid = runCatching { UUID.fromString(jobId) }.getOrNull() ?: return true
        return sql.current().prepareStatement(
            "SELECT attempts >= max_attempts FROM jobs WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                rs.next(); rs.getBoolean(1)
            }
        }
    }

    override fun countByStatus(status: String): Long =
        sql.current().prepareStatement("SELECT count(*) FROM jobs WHERE status = ?").use { ps ->
            ps.setString(1, status)
            ps.executeQuery().use { rs ->
                rs.next(); rs.getLong(1)
            }
        }

    override fun requeueExpiredLeases(olderThan: Instant, now: Instant): Int =
        sql.current().prepareStatement(
            """
            UPDATE jobs SET status = 'QUEUED', locked_at = NULL, locked_by = NULL
            WHERE status = 'LOCKED' AND locked_at < ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInstant(1, olderThan)
            ps.executeUpdate()
        }

    override fun hasPendingForType(type: String, payloadKey: String, payloadValue: String): Boolean =
        sql.current().prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1 FROM jobs
                WHERE type = ? AND status IN ('QUEUED', 'LOCKED')
                  AND payload ->> ? = ?
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, type)
            ps.setString(2, payloadKey)
            ps.setString(3, payloadValue)
            ps.executeQuery().use { rs ->
                rs.next(); rs.getBoolean(1)
            }
        }

    private fun objJson(map: Map<String, String>): String =
        kotlinx.serialization.json.buildJsonObject {
            map.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }.toString()

    private fun ResultSet.toJob(): Job = Job(
        id = getObject("id", UUID::class.java).toString(),
        type = getString("type"),
        payload = decodeRef(getString("payload")),
        priority = getInt("priority"),
        status = JobStatus.valueOf(getString("status")),
        attempts = getInt("attempts"),
        maxAttempts = getInt("max_attempts"),
        availableAt = instant("available_at"),
        lockedAt = getObject("locked_at")?.let { instant("locked_at") },
        lockedBy = getString("locked_by"),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        idempotencyKey = getString("idempotency_key"),
        createdAt = instant("created_at"),
    )

    private fun decodeRef(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            obj.entries.associate { (k, v) ->
                k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            }
        }.getOrDefault(emptyMap())
    }
}
