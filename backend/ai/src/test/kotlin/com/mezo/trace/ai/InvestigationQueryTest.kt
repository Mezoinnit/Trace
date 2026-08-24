package com.mezo.trace.ai

import com.mezo.trace.domain.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InvestigationQueryTest {

    private val provider = RuleBasedLocalProvider()

    @Test
    fun `plan §34 question extracts hint and month window`() {
        val q = provider.parseInvestigationQuery("What changed in Kotlin this month?")

        assertEquals("kotlin", q.entityHint)
        assertEquals(30, q.lastDays)
        // The subject scopes by identity; boilerplate must not leak into FTS.
        assertTrue(!q.textQuery.contains("kotlin") && !q.textQuery.contains("month"))
    }

    @Test
    fun `owner-slash hints survive and day ranges parse`() {
        val q = provider.parseInvestigationQuery(
            "Show me everything for androidx/androidx in the last 14 days",
        )
        assertEquals("androidx/androidx", q.entityHint)
        assertEquals(14, q.lastDays)
    }

    @Test
    fun `today maps to one day`() {
        assertEquals(1, provider.parseInvestigationQuery("What changed about compose today").lastDays)
    }

    @Test
    fun `no cue means unbounded`() {
        val q = provider.parseInvestigationQuery("Show security advisory items")
        assertNull(q.lastDays)
        assertTrue(q.textQuery.contains("security"))
    }

    @Test
    fun `blank is rejected`() {
        assertFailsWith<ValidationException> { provider.parseInvestigationQuery("") }
    }
}
