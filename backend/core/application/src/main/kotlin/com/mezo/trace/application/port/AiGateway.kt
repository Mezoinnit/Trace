package com.mezo.trace.application.port

import java.time.Instant

/**
 * Structured result of natural-language watch interpretation (plan §3.2,
 * §18). The user reviews and confirms before anything is created — AI never
 * silently creates monitoring rules.
 */
data class WatchIntentProposal(
    /** Exact endpoint when the input identified a source; null otherwise. */
    val endpointUri: String? = null,
    val adapterId: String? = null,
    val suggestedName: String? = null,
    /** Keyword include terms extracted from the request. */
    val keywordsInclude: List<String> = emptyList(),
    val keywordsExclude: List<String> = emptyList(),
    val minSignificance: String? = null,
    /** Things the interpreter could not determine — surfaced to the user. */
    val uncertainties: List<String> = emptyList(),
)

/**
 * Structured interpretation of a natural-language investigation question
 * (plan §3.4, §18 parseInvestigationQuery).
 */
data class InvestigationQuery(
    /** Free-text search terms for the event index. */
    val textQuery: String,
    /** Entity hint (name/alias fragment) resolved before searching. */
    val entityHint: String? = null,
    /** Recency window in days; null = unbounded. */
    val lastDays: Int? = null,
)

/**
 * Provider SPI (ADR-007). Implementations: deterministic LocalProvider now;
 * paid providers (Gemini/OpenAI/Anthropic) later via configuration only.
 */
interface AiProvider {
    val id: String
    val modelId: String

    fun parseWatchIntent(input: String): WatchIntentProposal

    /**
     * Semantic relevance between a watch's semantic description and an
     * event's text. 0.0..1.0. Deterministic providers may approximate.
     */
    fun semanticRelevance(description: String, eventText: String): Double

    fun parseInvestigationQuery(input: String): InvestigationQuery
}

/**
 * The ONLY path from the application layer to AI (ADR-007). Enforces input
 * limits, validates provider output shapes, records every operation for
 * reproducibility (plan Phase 10 rules), and degrades gracefully when no
 * provider is configured.
 */
interface AiGateway {
    val available: Boolean

    /**
     * @throws com.mezo.trace.domain.ValidationException when input exceeds
     *   limits or is blank; returns a proposal with uncertainties when the
     *   intent cannot be fully determined.
     */
    fun parseWatchIntent(input: String): WatchIntentProposal

    /** Null when no provider is available (caller degrades gracefully). */
    fun semanticRelevanceOrNull(description: String, eventText: String): Double?

    /** Null when no provider is available (caller degrades gracefully). */
    fun parseInvestigationQueryOrNull(input: String): InvestigationQuery?
}

/** One reproducible AI operation (DATA_MODEL ai_operations). */
data class AiOperationRecord(
    val operation: String,
    val provider: String,
    val model: String?,
    val inputRef: String?,
    val outputJson: Map<String, String>,
    val tokensUsed: Int,
    val latencyMs: Int,
    val cacheHit: Boolean,
    val at: Instant,
)

/**
 * Sink for AI operation records. Implementations own their transaction
 * boundary — the gateway calls this outside any database transaction.
 */
fun interface AiOperationRecorder {
    fun record(record: AiOperationRecord)
}
