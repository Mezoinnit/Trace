package com.mezo.trace.application.usecase.search

import com.mezo.trace.application.port.AiGateway
import com.mezo.trace.application.port.EntityRepository
import com.mezo.trace.application.port.EventRepository
import com.mezo.trace.application.port.InvestigationQuery
import com.mezo.trace.domain.Entity
import com.mezo.trace.domain.Event

/**
 * Investigation pipeline (plan §3.4, §1 investigation lifecycle):
 *
 * ```text
 * USER QUERY → QUERY INTERPRETATION (AI gateway)
 *            → ENTITY RESOLUTION (hint → entity by alias/name)
 *            → SEARCH (FTS + filters)
 *            → structured result
 * ```
 *
 * Deterministic search under an AI-interpreted envelope; without a provider
 * the raw question text is used as the free-text query (graceful degradation).
 */
class Investigate(
    private val events: EventRepository,
    private val entities: EntityRepository,
    private val aiGateway: AiGateway?,
    private val tx: com.mezo.trace.application.port.TxRunner,
) {

    data class Result(
        val interpretationText: String,
        val entityHint: String?,
        val lastDays: Int?,
        val events: List<Event>,
        val matchedEntities: List<Entity>,
    )

    fun execute(question: String, limit: Int = 50): Result {
        val interpreted = aiGateway?.parseInvestigationQueryOrNull(question.trim())
            ?: InvestigationQuery(
                textQuery = question.trim(),
                entityHint = null,
                lastDays = null,
            )

        return tx.run {
            // Entity hint resolves through aliases/canonical names first —
            // identity beats free text (plan §4.12).
            val hintEntity = interpreted.entityHint?.let { hint ->
                entities.search(hint, limit = 5).firstOrNull()
                    ?: entities.findByAlias(hint)
            }

            val resolvedEntityId = hintEntity?.id
            // Subject-scoped searches keep the empty query; filters do the
            // work (identity beats free text, plan §4.12). Degraded path falls
            // back to the raw question.
            val textForSearch = when {
                interpreted.textQuery.isNotBlank() -> interpreted.textQuery
                hintEntity != null -> ""
                else -> question.trim().take(200)
            }

            val from = interpreted.lastDays?.let { days ->
                java.time.Instant.now().minus(java.time.Duration.ofDays(days.toLong()))
            }

            val matchedEvents = events.searchEvents(
                query = textForSearch,
                filters = com.mezo.trace.application.port.EventSearchFilters(
                    entityId = resolvedEntityId,
                    from = from,
                ),
                limit = limit,
            )

            val matchedEntities = interpreted.entityHint?.let { entities.search(it, limit = 5) } ?: emptyList()

            Result(
                interpretationText = buildString {
                    append("events ")
                    if (resolvedEntityId != null) append("for '${interpreted.entityHint}' ")
                    if (interpreted.lastDays != null) append("within ${interpreted.lastDays}d ")
                    append("matching \"${textForSearch}\"")
                },
                entityHint = interpreted.entityHint,
                lastDays = interpreted.lastDays,
                events = matchedEvents,
                matchedEntities = matchedEntities,
            )
        }
    }
}
