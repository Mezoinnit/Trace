package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.domain.TrustClassification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfidenceCalculatorTest {

    @Test
    fun `official first-party source with stable ids scores high`() {
        val scored = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.OFFICIAL,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = true,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        assertTrue(scored.score >= 0.7, "expected HIGH band, got ${scored.score}")
        assertEquals("HIGH", scored.band)
    }

    @Test
    fun `corroboration across endpoints raises the score`() {
        val single = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.UNKNOWN,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = false,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        val corroborated = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.UNKNOWN,
                distinctSupportingEndpoints = 3,
                firstPartyConfirmation = false,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        assertTrue(corroborated.score > single.score)
        // Every input is recorded — inspectable, not a magic number (plan §15).
        assertTrue("corroboration" in corroborated.reasoning)
        assertEquals(1.0, corroborated.reasoning["corroboration"])
    }

    @Test
    fun `contradiction penalizes and untrusted sources score low`() {
        val contested = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.OFFICIAL,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = false,
                contradicted = true,
                hasStableExternalId = true,
            ),
        )
        val clean = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.OFFICIAL,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = false,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        assertTrue(contested.score < clean.score)

        val untrusted = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.UNTRUSTED,
                distinctSupportingEndpoints = 1,
                firstPartyConfirmation = false,
                contradicted = false,
                hasStableExternalId = false,
            ),
        )
        assertTrue(untrusted.score < 0.35, "untrusted sources must score low, got ${untrusted.score}")
    }

    @Test
    fun `scores stay within bounds for extreme inputs`() {
        val maxed = ConfidenceCalculator.calculate(
            ConfidenceCalculator.Inputs(
                sourceTrust = TrustClassification.FIRST_PARTY,
                distinctSupportingEndpoints = 10,
                firstPartyConfirmation = true,
                contradicted = false,
                hasStableExternalId = true,
            ),
        )
        assertTrue(maxed.score <= 1.0)
        assertEquals("VERY_HIGH", maxed.band)
    }
}
