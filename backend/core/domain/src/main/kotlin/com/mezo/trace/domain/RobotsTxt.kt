package com.mezo.trace.domain

/**
 * robots.txt directives that both resolution-time discovery and fetch-time
 * politeness depend on (plan §8).
 *
 * Only the `Sitemap:` directives are parsed here; crawler allow/disallow
 * evaluation stays with the web adapter, which owns the fetch. Keeping the
 * directive parsing in the dependency-free domain layer means the resolver and
 * the adapter can never drift into two subtly different readings of the same
 * file (Rule 1: one contract).
 *
 * robots.txt is untrusted input: parsing is bounded, best-effort, and never a
 * security control (ADR-011 — SSRF defense is the fetcher's job).
 */
object RobotsTxt {

    private const val SITEMAP_PREFIX = "Sitemap:"

    /**
     * Absolute `Sitemap:` directives, in file order, de-duplicated.
     *
     * Relative values are ignored — sitemap URLs must be absolute per the
     * robots exclusion protocol. Comments are stripped, so a commented-out
     * directive never matches.
     */
    fun sitemapUrls(robotsText: String?): List<String> {
        if (robotsText.isNullOrBlank()) return emptyList()
        return robotsText.lines().mapNotNull { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (!line.startsWith(SITEMAP_PREFIX, ignoreCase = true)) return@mapNotNull null
            val url = line.substringAfter(':').trim()
            if (url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)
            ) {
                url
            } else {
                null
            }
        }.distinct()
    }
}
