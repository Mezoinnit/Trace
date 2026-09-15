package com.mezo.trace.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobotsTxtTest {

    @Test
    fun `missing or blank robots text yields no directives`() {
        assertTrue(RobotsTxt.sitemapUrls(null).isEmpty())
        assertTrue(RobotsTxt.sitemapUrls("").isEmpty())
        assertTrue(RobotsTxt.sitemapUrls("   \n  ").isEmpty())
    }

    @Test
    fun `absolute sitemap directives are returned in file order and deduplicated`() {
        val robots = """
            User-agent: *
            Disallow:

            Sitemap: https://site.example.com/sitemap.xml
            sitemap: https://site.example.com/sitemap.xml
            Sitemap: http://site.example.com/extra.xml
        """.trimIndent()

        assertEquals(
            listOf(
                "https://site.example.com/sitemap.xml",
                "http://site.example.com/extra.xml",
            ),
            RobotsTxt.sitemapUrls(robots),
        )
    }

    @Test
    fun `relative and commented directives are ignored`() {
        val robots = """
            User-agent: *
            Disallow:

            Sitemap: https://site.example.com/sitemap.xml
            Sitemap: /relative/ignored.xml
            # Sitemap: https://commented.example.com/x.xml
        """.trimIndent()

        assertEquals(listOf("https://site.example.com/sitemap.xml"), RobotsTxt.sitemapUrls(robots))
    }

    @Test
    fun `trailing comments are stripped from directives`() {
        val robots = "Sitemap: https://site.example.com/sitemap.xml # primary index"

        assertEquals(listOf("https://site.example.com/sitemap.xml"), RobotsTxt.sitemapUrls(robots))
    }
}
