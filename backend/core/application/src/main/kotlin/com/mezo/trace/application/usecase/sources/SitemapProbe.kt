package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.domain.RobotsTxt
import com.mezo.trace.domain.Urls

/**
 * Bounded sitemap discovery (plan §8 step 6). Used by the resolver BEFORE
 * declaring a page degraded; also usable for the adapter's own discovery.
 *
 * Preference: robots.txt `Sitemap:` directives, then well-known paths.
 * Never crawls URL sets — existence probe only (Rule 6).
 */
class SitemapProbe(private val fetcher: HttpFetcher) {

    /**
     * Returns the discovered sitemap URL after probing robots.txt directives
     * and standard paths, prefetching a bounded amount of each.
     */
    fun discover(pageUrl: String): String? {
        val origin = runCatching { Urls.normalize(pageUrl) }.getOrNull()?.resolve("/")?.toString()
            ?: return null

        robotsSitemaps(origin).forEach { candidate ->
            val found = probe(candidate)
            if (found != null) return found
        }

        val candidates = listOf(
            "${origin}sitemap.xml",
            "${origin}sitemap_index.xml",
            "${origin}sitemap_index.xml.gz",
            "${origin}sitemap.php",
        )
        candidates.forEach { candidate ->
            val found = probe(candidate)
            if (found != null) return found
        }
        return null
    }

    private fun robotsSitemaps(origin: String): List<String> = runCatching {
        val response = fetcher.fetch(
            FetchRequest(url = "${origin}robots.txt", maxBytes = 64 * 1024),
        )
        if (response.statusCode !in 200..299) return@runCatching emptyList()
        // Shared with the web adapter's robots handling (domain.RobotsTxt) so
        // resolution and fetching never read the file differently.
        RobotsTxt.sitemapUrls(response.body.decodeToString())
    }.getOrDefault(emptyList())

    private fun probe(url: String): String? = runCatching {
        val response = fetcher.fetch(FetchRequest(url = url, maxBytes = 64 * 1024))
        if (response.statusCode !in 200..299) return@runCatching null
        val body = decode(response.body)
        // Sitemaps may carry an XML declaration before the root element.
        if (SITEMAP_ROOT.containsMatchIn(body.take(256))) url else null
    }.getOrNull()

    /** Gzipped sitemaps (.gz) are decompressed; input is already size-capped. */
    private fun decode(body: ByteArray): String {
        val gzipped = body.size >= 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()
        return if (!gzipped) body.decodeToString()
        else runCatching {
            java.util.zip.GZIPInputStream(body.inputStream()).use { it.readBytes().decodeToString() }
        }.getOrDefault("")
    }

    private val SITEMAP_ROOT = Regex(
        """^\s*(<\?xml[^>]*\?>)?\s*<(urlset|sitemapindex)[\s>]""",
        RegexOption.IGNORE_CASE,
    )
}
