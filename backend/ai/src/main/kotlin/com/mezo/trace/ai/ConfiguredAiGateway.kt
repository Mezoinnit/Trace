package com.mezo.trace.ai

import com.mezo.trace.application.port.AiGateway
import com.mezo.trace.application.port.AiOperationRecord
import com.mezo.trace.application.port.AiOperationRecorder
import com.mezo.trace.application.port.AiProvider
import com.mezo.trace.application.port.WatchIntentProposal
import com.mezo.trace.domain.ValidationException
import java.security.MessageDigest
import java.time.Instant

/**
 * The single AI entry point (ADR-007, plan §18).
 *
 * - provider selection is configuration-driven (`AI_PROVIDER`, default local)
 * - input limits enforced here (Rule 6)
 * - every call is recorded to `ai_operations` for reproducibility
 * - [available] reflects configuration; the matcher degrades gracefully
 */
class ConfiguredAiGateway(
    private val providers: List<AiProvider>,
    private val selectedProviderId: String = "local",
    private val operations: AiOperationRecorder? = null,
    private val clock: () -> Instant = Instant::now,
    private val maxInputChars: Int = MAX_INPUT_CHARS,
) : AiGateway {

    private val selected: AiProvider? by lazy {
        providers.firstOrNull { it.id == selectedProviderId }
            ?: providers.firstOrNull()
    }

    override val available: Boolean get() = selected != null

    override fun parseWatchIntent(input: String): WatchIntentProposal {
        val provider = selected ?: throw ValidationException("input", "AI layer unavailable")
        if (input.isBlank()) throw ValidationException("input", "describe what to monitor")
        if (input.length > maxInputChars) {
            throw ValidationException("input", "description exceeds $maxInputChars characters")
        }

        val startedNanos = System.nanoTime()
        val proposal = provider.parseWatchIntent(input)
        record(provider, "PARSE_WATCH_INTENT", input, proposal, elapsedMs(startedNanos))

        // Defensive validation of provider output (untrusted even when ours).
        require(proposal.keywordsInclude.size <= 20) { "provider returned too many keywords" }
        return proposal.copy(
            keywordsInclude = proposal.keywordsInclude.map { it.lowercase().take(64) },
            keywordsExclude = proposal.keywordsExclude.map { it.lowercase().take(64) },
        )
    }

    override fun semanticRelevanceOrNull(description: String, eventText: String): Double? {
        val provider = selected ?: return null
        if (description.isBlank()) return null
        val startedNanos = System.nanoTime()
        val score = provider.semanticRelevance(description.take(maxInputChars), eventText.take(MAX_EVENT_TEXT))
        record(provider, "SEMANTIC_RELEVANCE", "$description|$eventText", mapOf("score" to score.toString()), elapsedMs(startedNanos))
        return score.coerceIn(0.0, 1.0)
    }

    private fun record(
        provider: AiProvider,
        operation: String,
        rawInput: String,
        output: Any?,
        latencyMs: Int,
    ) {
        val sink = operations ?: return
        runCatching {
            val outputMap: Map<String, String> = when (output) {
                is WatchIntentProposal -> mapOf(
                    "endpoint_uri" to output.endpointUri.orEmpty(),
                    "adapter_id" to output.adapterId.orEmpty(),
                    "keywords" to output.keywordsInclude.joinToString(","),
                    "uncertainties" to output.uncertainties.joinToString("|"),
                )

                else -> output as? Map<String, String> ?: emptyMap()
            }
            sink.record(
                AiOperationRecord(
                    operation = operation,
                    provider = provider.id,
                    model = provider.modelId,
                    inputRef = sha256(rawInput),
                    outputJson = outputMap,
                    tokensUsed = 0, // deterministic providers consume no tokens
                    latencyMs = latencyMs,
                    cacheHit = false,
                    at = clock(),
                ),
            )
        } // recording failures never break product flow
    }

    private fun elapsedMs(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / 1_000_000).toInt().coerceAtLeast(0)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_INPUT_CHARS = 500
        const val MAX_EVENT_TEXT = 1_000
    }
}
