package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.application.port.EventRepository
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.Event

/**
 * Multi-signal event clustering (plan §13). A single similarity threshold is
 * never the only decision mechanism — signals combine as rules:
 *
 * 1. **Identifier signal (hard)**: same normalized title AND same event type
 *    AND temporal proximity → same real-world event.
 * 2. **Title similarity**: Jaccard over normalized title tokens.
 * 3. **Temporal proximity**: hours between candidate occurrences.
 * 4. **Type match** is mandatory for any attach decision.
 *
 * Deterministic and AI-free; semantic similarity joins in Phase 10 behind
 * this seam. Candidates are bounded (recent window, max scan) per Rule 6.
 */
class EventClusterer(private val events: EventRepository, private val tx: TxRunner) {

    data class Signals(
        val titleJaccard: Double,
        val hoursApart: Double?,
        val sameType: Boolean,
    )

    data class Decision(
        val attachTo: Event?,
        val signals: Signals,
    )

    fun findCluster(
        type: String,
        item: NormalizedItem,
        occurredAt: java.time.Instant?,
        sourceId: String,
    ): Decision {
        return tx.run {
            // Candidates are scoped to the same source: similarly-titled
            // releases from UNRELATED repositories must not over-merge.
            // Cross-source convergence happens via exact identifier matches.
            val candidates = events.latestDiscoveredBySource(sourceId, CANDIDATE_LIMIT)
                .filter { it.type == type && it.status != com.mezo.trace.domain.EventStatus.SUPERSEDED }
                .take(CANDIDATE_SCAN)

            val incomingVersions = versionsOf(item.title)
            var best: Pair<Event, Double>? = null
            for (candidate in candidates) {
                // Hard gate: when BOTH titles carry version identifiers, they
                // must match — v3.0.0 and v3.1.0 are never the same event.
                val candidateVersions = versionsOf(candidate.title)
                if (candidateVersions.isNotEmpty() && incomingVersions.isNotEmpty() &&
                    candidateVersions != incomingVersions
                ) {
                    continue
                }
                if (!withinWindow(candidate, occurredAt)) continue
                val jaccard = jaccard(candidate.title, item.title)
                if (jaccard >= TITLE_THRESHOLD) {
                    if (best == null || jaccard > best.second) best = candidate to jaccard
                }
            }

            Decision(
                attachTo = best?.first,
                signals = Signals(
                    titleJaccard = best?.second ?: 0.0,
                    hoursApart = occurredAt?.let { occ ->
                        best?.first?.occurredAt?.let { existing ->
                            kotlin.math.abs(java.time.Duration.between(existing, occ).toHours().toDouble())
                        }
                    },
                    sameType = true,
                ),
            )
        }
    }

    private fun withinWindow(candidate: Event, occurredAt: java.time.Instant?): Boolean {
        val reference = occurredAt ?: java.time.Instant.now()
        val candidateTime = candidate.occurredAt ?: candidate.discoveredAt
        return kotlin.math.abs(java.time.Duration.between(candidateTime, reference).toHours()) <= WINDOW_HOURS
    }

    internal fun jaccard(a: String?, b: String?): Double {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return 0.0
        val tokensA = normalizeTokens(a)
        val tokensB = normalizeTokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        return intersection / union
    }

    private val VERSION_TOKEN = Regex("""v?\d+(?:\.\d+)+""", RegexOption.IGNORE_CASE)

    internal fun versionsOf(title: String?): Set<String> {
        if (title.isNullOrBlank()) return emptySet()
        return VERSION_TOKEN.findAll(title).map { it.value.lowercase() }.toSet()
    }

    private fun normalizeTokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toSet()

    companion object {
        /** Attach when titles are at least this similar (plus type + time gates). */
        const val TITLE_THRESHOLD = 0.6

        /** Candidate events older than this are outside clustering range. */
        const val WINDOW_HOURS = 72L

        const val CANDIDATE_LIMIT = 100
        const val CANDIDATE_SCAN = 50

        private val STOP_WORDS = setOf("the", "a", "an", "and", "or", "of", "for", "in", "on", "to", "is", "released")
    }
}
