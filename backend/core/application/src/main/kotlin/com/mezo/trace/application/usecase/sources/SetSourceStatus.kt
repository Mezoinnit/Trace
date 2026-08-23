package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.SourceStatus
import com.mezo.trace.domain.UserId

/**
 * Explicit enable/disable of a source. Disabling cascades to its endpoints —
 * a visible, audited user action, never silent (Rule 7).
 */
class SetSourceStatus(
    private val sources: SourceRepository,
    private val endpoints: SourceEndpointRepository,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val clock: ClockPort,
) {

    data class StatusResult(
        val status: SourceStatus,
        val affectedEndpoints: Int,
    )

    fun execute(actor: UserId, sourceId: String, newStatus: SourceStatus): StatusResult {
        return tx.run {
            val source = sources.findById(sourceId)
                ?: throw com.mezo.trace.application.AppError.NotFound("source")
            if (source.status == newStatus) {
                val count = endpoints.listBySourceId(source.id).count { it.status == newStatus }
                return@run StatusResult(newStatus, count)
            }

            sources.setStatus(source.id, newStatus.name, clock.now())

            var affected = 0
            endpoints.listBySourceId(source.id).forEach { endpoint ->
                if (endpoint.status != newStatus) {
                    endpoints.setStatus(endpoint.id, newStatus.name, clock.now())
                    affected++
                }
            }

            audit.append(
                AuditEntry(
                    action = if (newStatus == SourceStatus.ACTIVE) "SOURCE_ENABLED" else "SOURCE_DISABLED",
                    actorType = ActorType.USER,
                    actorId = actor.value,
                    resourceType = "source",
                    resourceId = source.id,
                    metadata = mapOf("affected_endpoints" to affected.toString()),
                ),
            )
            StatusResult(newStatus, affected)
        }
    }
}
