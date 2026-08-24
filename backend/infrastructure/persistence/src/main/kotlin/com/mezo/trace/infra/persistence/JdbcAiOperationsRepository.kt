package com.mezo.trace.infra.persistence

import java.time.Instant
import java.util.UUID

class JdbcAiOperationsRepository(private val sql: SqlConnections) {

    fun record(
        operation: String,
        provider: String,
        model: String?,
        inputRef: String?,
        outputJson: Map<String, String>,
        tokensUsed: Int,
        latencyMs: Int,
        cacheHit: Boolean,
        at: Instant,
    ) {
        sql.current().prepareStatement(
            """
            INSERT INTO ai_operations (id, operation, provider, model, input_ref,
                                       output_json, tokens_used, latency_ms, cache_hit, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, operation)
            ps.setString(3, provider)
            ps.setNullableString(4, model)
            ps.setNullableString(5, inputRef)
            ps.setString(
                6,
                kotlinx.serialization.json.buildJsonObject {
                    outputJson.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                }.toString(),
            )
            ps.setInt(7, tokensUsed)
            ps.setInt(8, latencyMs)
            ps.setBoolean(9, cacheHit)
            ps.setInstant(10, at)
            ps.executeUpdate()
        }
    }
}
