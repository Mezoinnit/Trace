package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AdapterSelector
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.application.port.FetchResult
import com.mezo.trace.application.port.MetricsPort
import com.mezo.trace.application.port.NoopMetrics
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
    private val ingestEvidence: com.mezo.trace.application.usecase.evidence.IngestEvidence,
    private val deriveEvents: com.mezo.trace.application.usecase.intelligence.DeriveEventsFromEvidence,
    private val matchWatches: com.mezo.trace.application.usecase.intelligence.MatchWatchesForEvent,
    private val tx: TxRunner,
    private val clock: ClockPort,
    private val metrics: MetricsPort = NoopMetrics(),
) {

    sealed class Outcome {
        data class Success(
            val result: com.mezo.trace.application.port.FetchResult,
            val latencyMs: Long,
            val evidenceCreated: Int = 0,
            val eventsDerived: Int = 0,
            val alertsCreated: Int = 0,
        ) : Outcome()

        data class Failed(val failureState: String, val reason: String) : Outcome()
    }

    fun execute(endpointId: String): Outcome {
        val endpoint = tx.run { loadEndpoint(endpointId) }

        val startedAt = System.nanoTime()
        var evidenceCreated = 0
        var eventsDerived = 0
        var alertsCreated = 0

        val outcome: Outcome = try {
            // The adapter persisted on the endpoint is authoritative.
            val adapter = adapterSelector.byId(endpoint.adapter)
                ?: return fail(endpoint, "PARSER_FAILED", "unknown adapter ${endpoint.adapter}")

            val knownEtag = tx.run { health.findByEndpointId(endpoint.id)?.lastEtag }

            // Bounded pagination backfill (plan Phase 4, Rule 6): follow the
            // adapter cursor for at most MAX_BACKFILL_PAGES fetches per cycle.
            // Newest-first APIs settle on a single page in steady state; the
            // loop covers initial backfill deterministically.
            var items = mutableListOf<com.mezo.trace.application.port.NormalizedItem>()
            var lastEtag: String? = null
            var notes = mutableListOf<String>()
            var cursor: String? = null
            for (page in 0 until MAX_BACKFILL_PAGES) {
                val result = adapter.fetch(
                    endpoint.endpointUri,
                    cursor = cursor,
                    knownEtag = knownEtag.takeIf { page == 0 },
                )
                items.addAll(result.items)
                lastEtag = result.etag ?: lastEtag
                notes.addAll(result.notes)
                cursor = result.nextCursor
                if (cursor == null || result.items.isEmpty()) break
            }

            // Persist items, derive events, match watches (Phase 3–4).
            val summary = ingestEvidence.execute(endpoint.sourceId, endpoint.id, items)
            evidenceCreated = summary.created
            if (summary.touched.isNotEmpty()) {
                val events = deriveEvents.execute(endpoint, summary.touched)
                eventsDerived = events.size
                events.forEach { event ->
                    alertsCreated += matchWatches.execute(event).alertsCreated
                }
            }
            Outcome.Success(
                com.mezo.trace.application.port.FetchResult(items, etag = lastEtag, notes = notes),
                elapsedMs(startedAt),
                evidenceCreated,
                eventsDerived,
                alertsCreated,
            )
        } catch (failure: FetchFailure) {
            Outcome.Failed(mapKind(failure.kind), failure.message ?: failure.kind.name)
        } catch (rejected: com.mezo.trace.application.port.EndpointRejected) {
            Outcome.Failed("PARSER_FAILED", rejected.message ?: "endpoint rejected")
        } catch (unexpected: Exception) {
            Outcome.Failed("NETWORK_FAILED", unexpected.message ?: "unexpected fetch error")
        }

        // Record outcome in a fresh short transaction (Rule 7).
        metrics.increment(
            "fetch_total",
            mapOf(
                "outcome" to when (outcome) {
                    is Outcome.Success -> "success"
                    is Outcome.Failed -> outcome.failureState.lowercase()
                },
            ),
        )
        tx.run {
            when (outcome) {
                // 304 responses arrive as empty results carrying the stored
                // etag — still a success for health purposes.
                is Outcome.Success -> {
                    // Persist normalized items as evidence (Phase 3): the
                    // ingestion is idempotent per (endpoint, external_id).
                    if (outcome.result.items.isNotEmpty()) {
                        ingestEvidence.execute(
                            sourceId = endpoint.sourceId,
                            endpointId = endpoint.id,
                            items = outcome.result.items,
                        )
                    }
                    health.applySuccess(
                        endpoint.id,
                        clock.now(),
                        outcome.latencyMs.toInt(),
                        outcome.result.items.size,
                    )
                    health.storeEtag(endpoint.id, outcome.result.etag)

                    // Parser visibility (Phase 13, plan §8 step 12): adapter
                    // notes drive the outcome. Empty PAGE+304 (`not_modified`)
                    // must not falsely degrade — notes are authoritative, and
                    // they are recorded either way so operators can see *how*
                    // items were extracted (Rule 7).
                    health.applyParserOutcome(
                        endpoint.id,
                        clock.now(),
                        outcome.result.notes,
                        failed = outcome.result.notes.any { it in PARSER_FAILURE_NOTES },
                    )
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

    companion object {
        /** Page 1 + up to this many cursor follow-ups per cycle (Rule 6). */
        const val MAX_BACKFILL_PAGES = 3

        /** Adapter notes that count as parser failures (plan §8 step 12). */
        private val PARSER_FAILURE_NOTES = setOf(
            "robots_disallowed",
            "no_extractable_article_content",
            "robots_unparseable_url",
        )
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000
}
