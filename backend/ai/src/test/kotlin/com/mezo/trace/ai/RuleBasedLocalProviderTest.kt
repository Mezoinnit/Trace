package com.mezo.trace.ai

import com.mezo.trace.domain.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleBasedLocalProviderTest {

    private val provider = RuleBasedLocalProvider()

    @Test
    fun `plan §59 sentence resolves endpoint and keyword filter`() {
        val proposal = provider.parseWatchIntent(
            "Monitor new releases in androidx/androidx and notify me when Compose changes",
        )

        assertEquals("github://androidx/androidx/releases", proposal.endpointUri)
        assertEquals("github", proposal.adapterId)
        assertTrue(proposal.keywordsInclude.contains("compose"), proposal.keywordsInclude.toString())
        assertTrue(proposal.uncertainties.isEmpty())
    }

    @Test
    fun `bare repo token and quoted keywords work`() {
        val p = provider.parseWatchIntent(
            "Watch JetBrains/kotlin for \"security advisory\" items",
        )

        assertEquals("github://JetBrains/kotlin/releases", p.endpointUri)
        assertTrue(p.keywordsInclude.contains("security advisory"))
    }

    @Test
    fun `unrecognizable source lands in uncertainties`() {
        val p = provider.parseWatchIntent("Tell me when the price of coffee changes about arabica")

        assertEquals(null, p.endpointUri)
        assertTrue(p.uncertainties.any { it.contains("no recognizable source") })
        assertTrue(p.keywordsInclude.contains("coffee") || p.keywordsInclude.contains("arabica"))
    }

    @Test
    fun `major-only raises the significance floor`() {
        val p = provider.parseWatchIntent(
            "Monitor androidx/androidx releases, only major ones about compose",
        )
        assertEquals("HIGH", p.minSignificance)
    }

    @Test
    fun `blank input is a validation error`() {
        assertFailsWith<ValidationException> { provider.parseWatchIntent("   ") }
    }

    @Test
    fun `semantic relevance bounds and ordering`() {
        val full = provider.semanticRelevance("compose compiler update", "Compose Compiler update shipped today")
        val none = provider.semanticRelevance("compose compiler update", "NVIDIA earnings beat expectations")
        val partial = provider.semanticRelevance("compose release", "Kotlin update and compose notes")

        assertTrue(full > 0.8, "full=$full")
        assertEquals(0.0, none)
        assertTrue(partial in 0.3..1.0)
    }
}
