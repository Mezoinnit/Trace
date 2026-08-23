package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AdapterSelector
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.application.port.FetchResult
import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceHealthRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.SourceEndpoint

/**
 * Executes one bounded fetch cycle for an exact endpoint and records the
 * outcome on source health (plan §9, Rule 7 — failures are never silent).
 *
 * Network I/O happens OUTSIDE database transactions: reads run in a short
 * transaction, the fetch is unguarded, then the outcome is written in a
 * second short transaction.
 */
class FetchEndpointNow(
    private val endpoints: SourceEndpointRepository,
    private val health: SourceHealthRepository,
    private val audit: AuditLogRepository,
    private val adapterSelector: AdapterSelector,
    private val tx: TxRunner,
    private val clock: ClockPort,
) {

    sealed class Outcome {
        data class Success(val result: FetchResult, val latencyMs: Long) : Outcome()
        data class Failed(val failureState: String, val reason: String) : Outcome()
    }

    fun execute(endpointId: String): Outcome {
        val endpoint = tx.run { loadEndpoint(endpointId) }

        val startedAt = System.nanoTime()
        val outcome = try {
            // The adapter persisted on the endpoint is authoritative.
            val adapter = adapterSelector.byId(endpoint.adapter)
                ?: return fail(endpoint, "PARSER_FAILED", "unknown adapter ${endpoint.adapter}")

            val knownEtag = tx.run { health.findByEndpointId(endpoint.id)?.lastEtag }
            val result = adapter.fetch(endpoint.endpointUri, cursor = null, knownEtag = knownEtag)
            Outcome.Success(result, elapsedMs(startedAt))
        } catch (failure: FetchFailure) {
            Outcome.Failed(mapKind(failure.kind), failure.message ?: failure.kind.name)
        } catch (rejected: com.mezo.trace.application.port.EndpointRejected) {
            Outcome.Failed("PARSER_FAILED", rejected.message ?: "endpoint rejected")
        } catch (unexpected: Exception) {
            Outcome.Failed("NETWORK_FAILED", unexpected.message ?: "unexpected fetch error")
        }

        // Record outcome in a fresh short transaction (Rule 7).
        tx.run {
            when (outcome) {
                // 304 responses arrive as empty results carrying the stored
                // etag — still a success for health purposes.
                is Outcome.Success -> {
                    health.applySuccess(
                        endpoint.id,
                        clock.now(),
                        outcome.latencyMs.toInt(),
                        outcome.result.items.size,
                    )
                    health.storeEtag(endpoint.id, outcome.result.etag)
                }

                is Outcome.Failed -> {
                    health.applyFailure(endpoint.id, clock.now(), outcome.failureState)
                    audit.append(
                        AuditEntry(
                            action = "SOURCE_FETCH_FAILED",
                            actorType = ActorType.JOB,
                            resourceType = "source_endpoint",
                            resourceId = endpoint.id,
                            metadata = mapOf("state" to outcome.failureState, "reason" to outcome.reason.take(200)),
                        ),
                    )
                }
            }
        }
        return outcome
    }

    private fun loadEndpoint(endpointId: String): SourceEndpoint =
        endpoints.findById(endpointId) ?: throw AppError.NotFound("source endpoint")

    private fun fail(endpoint: SourceEndpoint, state: String, reason: String): Outcome.Failed {
        tx.run { health.applyFailure(endpoint.id, clock.now(), state) }
        return Outcome.Failed(state, reason)
    }

    private fun mapKind(kind: FetchFailure.Kind): String = when (kind) {
        FetchFailure.Kind.RATE_LIMITED -> "RATE_LIMITED"
        FetchFailure.Kind.AUTH_REQUIRED -> "AUTH_REQUIRED"
        FetchFailure.Kind.TIMEOUT -> "NETWORK_FAILED"
        FetchFailure.Kind.NETWORK -> "NETWORK_FAILED"
        FetchFailure.Kind.DNS_FAILURE -> "NETWORK_FAILED"
        FetchFailure.Kind.TOO_LARGE -> "DEGRADED"
        FetchFailure.Kind.BLOCKED_PRIVATE_ADDRESS -> "BLOCKED"
        FetchFailure.Kind.UNSUPPORTED_SCHEME -> "BLOCKED"
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000
}
