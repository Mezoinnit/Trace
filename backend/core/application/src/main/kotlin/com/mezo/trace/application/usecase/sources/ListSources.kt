package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceHealthRepository
import com.mezo.trace.application.port.SourceRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.Source
import com.mezo.trace.domain.SourceEndpoint
import com.mezo.trace.domain.SourceHealth

/**
 * Global source registry views (shared intelligence, ADR-001).
 */
class ListSources(
    private val sources: SourceRepository,
    private val endpoints: SourceEndpointRepository,
    private val tx: TxRunner,
) {

    data class SourceWithEndpoints(
        val source: Source,
        val endpoints: List<SourceEndpoint>,
    )

    fun execute(): List<SourceWithEndpoints> = tx.run {
        sources.listAll().map { source ->
            SourceWithEndpoints(source, endpoints.listBySourceId(source.id))
        }
    }
}

class GetSourceDetail(
    private val sources: SourceRepository,
    private val endpoints: SourceEndpointRepository,
    private val health: SourceHealthRepository,
    private val tx: TxRunner,
) {

    data class EndpointWithHealth(
        val endpoint: SourceEndpoint,
        val health: SourceHealth?,
    )

    data class Detail(
        val source: Source,
        val endpoints: List<EndpointWithHealth>,
    )

    fun execute(sourceId: String): Detail {
        return tx.run {
            val source = sources.findById(sourceId)
                ?: throw com.mezo.trace.application.AppError.NotFound("source")
            Detail(
                source = source,
                endpoints = endpoints.listBySourceId(source.id).map { endpoint ->
                    EndpointWithHealth(endpoint, health.findByEndpointId(endpoint.id))
                },
            )
        }
    }
}
