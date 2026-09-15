package com.mezo.trace.adapters.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotsParserTest {

    @Test
    fun `empty or blank robots means allowed`() {
        assertTrue(RobotsParser.isAllowed(null, "/any"))
        assertTrue(RobotsParser.isAllowed("", "/any"))
        assertTrue(RobotsParser.isAllowed("   \n  ", "/any"))
    }

    @Test
    fun `star group disallow blocks matching paths`() {
        val robots = """
            User-agent: *
            Disallow: /private/
            Allow: /
        """.trimIndent()

        assertFalse(RobotsParser.isAllowed(robots, "/private/secret"))
        assertTrue(RobotsParser.isAllowed(robots, "/public/page"))
        assertTrue(RobotsParser.isAllowed(robots, "/"))
    }

    @Test
    fun `specific agent group wins over star`() {
        val robots = """
            User-agent: TraceIntelligenceBot
            Disallow: /
            
            User-agent: *
            Disallow: /private/
        """.trimIndent()

        assertFalse(RobotsParser.isAllowed(robots, "/anything"))
    }

    @Test
    fun `longest match wins between allow and disallow`() {
        val robots = """
            User-agent: *
            Disallow: /section/
            Allow: /section/public/
        """.trimIndent()

        assertFalse(RobotsParser.isAllowed(robots, "/section/other"))
        assertTrue(RobotsParser.isAllowed(robots, "/section/public/item"))
    }

    @Test
    fun `comments and case are ignored`() {
        val robots = """
            # global policy
            USER-AGENT: *
            DISALLOW: /admin  # no admins
        """.trimIndent()

        assertFalse(RobotsParser.isAllowed(robots, "/admin/panel"))
        assertTrue(RobotsParser.isAllowed(robots, "/home"))
    }

    @Test
    fun `unknown agents do not inherit other groups`() {
        val robots = """
            User-agent: Googlebot
            Disallow: /
        """.trimIndent()

        assertTrue(RobotsParser.isAllowed(robots, "/any"))
    }
}
