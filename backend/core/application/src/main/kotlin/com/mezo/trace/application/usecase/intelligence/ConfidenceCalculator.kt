package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.domain.Event
import com.mezo.trace.domain.TrustClassification

/**
 * Deterministic, inspectable confidence scoring (plan §15).
 *
 * Confidence is NOT source trust — trust is one input among several. Every
 * input is recorded in the reasoning map so any score can be explained:
 *
 * ```json
 * { "source_quality": 0.8, "corroboration": 0.5, "first_party": 1.0,
 *   "contradiction_penalty": 0.0, "identifier_match": 0.6 }
 * ```
 */
object ConfidenceCalculator {

    data class Inputs(
        val sourceTrust: TrustClassification,
        val distinctSupportingEndpoints: Int,
        val firstPartyConfirmation: Boolean,
        val contradicted: Boolean,
        val hasStableExternalId: Boolean,
    )

    data class Scored(
        val score: Double,
        val band: String,
        val reasoning: Map<String, Double>,
    )

    fun calculate(inputs: Inputs): Scored {
        val reasoning = linkedMapOf<String, Double>()

        reasoning["source_quality"] = when (inputs.sourceTrust) {
            TrustClassification.FIRST_PARTY -> 1.0
            TrustClassification.OFFICIAL -> 0.8
            TrustClassification.ESTABLISHED -> 0.65
            TrustClassification.COMMUNITY -> 0.45
            TrustClassification.UNKNOWN -> 0.3
            TrustClassification.UNTRUSTED -> 0.1
        }

        // Independent corroboration saturates at three distinct endpoints.
        // A single report is the normal starting state, not a red flag.
        reasoning["corroboration"] = when {
            inputs.distinctSupportingEndpoints >= 3 -> 1.0
            inputs.distinctSupportingEndpoints == 2 -> 0.8
            else -> 0.6
        }

        reasoning["first_party"] = if (inputs.firstPartyConfirmation) 1.0 else 0.35
        reasoning["contradiction_penalty"] = if (inputs.contradicted) -0.4 else 0.0
        reasoning["identifier_match"] = if (inputs.hasStableExternalId) 0.9 else 0.4

        val weights = mapOf(
            "source_quality" to 0.30,
            "corroboration" to 0.25,
            "first_party" to 0.15,
            "contradiction_penalty" to 0.10,
            "identifier_match" to 0.20,
        )
        var score = 0.0
        weights.forEach { (key, weight) -> score += reasoning[key]!! * weight }

        val bounded = score.coerceIn(0.0, 1.0)
        return Scored(bounded, band(bounded), reasoning)
    }

    /** Maps a 0..1 score onto the stored confidence enum. */
    fun band(score: Double): String = when {
        score >= 0.85 -> "VERY_HIGH"
        score >= 0.7 -> "HIGH"
        score >= 0.45 -> "MEDIUM"
        score >= 0.25 -> "LOW"
        else -> "VERY_LOW"
    }

    fun confidenceFor(event: Event): Double = event.confidenceScore ?: 0.45
}
