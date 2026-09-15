package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider
import com.mezo.trace.domain.TrustClassification

/**
 * A structured, user-confirmable source proposal (plan §3.2, §7).
 * The system never silently broadens an exact endpoint.
 */
data class SourceProposal(
    val inputUrl: String,
    val normalizedUrl: String,
    val provider: Provider,
    val sourceType: String,
    val canonicalIdentity: String,
    val endpointUri: String,
    val displayUrl: String,
    val endpointType: String,
    val adapterId: String,
    val capabilities: Set<Capability>,
    val trust: TrustClassification,
    val displayName: String?,
    val notes: List<String>,
)

/**
 * URL → provider → exact endpoint resolution (plan §7):
 *
 * ```text
 * normalize → detect provider → recognize endpoint →
 * detect structured alternatives → resolve exact resource →
 * determine capabilities → select adapter
 * ```
 *
 * All probing fetches go through the SSRF-safe [HttpFetcher] and are bounded.
 */
class ResolveSource(private val fetcher: HttpFetcher) {

    private val sitemaps = SitemapProbe(fetcher)

    fun execute(rawUrl: String): SourceProposal {
        val normalized = try {
            com.mezo.trace.domain.Urls.normalize(rawUrl.trim()).toString()
        } catch (e: IllegalArgumentException) {
            throw com.mezo.trace.domain.ValidationException("input", "invalid url")
        }

        githubRepository(normalized)?.let { return it }
        return probeWebSource(normalized)
    }

    // ---- GitHub: host-pattern detection, strict endpoint recognition ----

    private fun githubRepository(normalizedUrl: String): SourceProposal? {
        val uri = java.net.URI.create(normalizedUrl)
        val host = uri.host ?: return null
        if (host != "github.com" && host != "www.github.com") return null

        val segments = (uri.path ?: "").trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) {
            throw com.mezo.trace.domain.ValidationException(
                "input",
                "github urls must identify a repository (owner/repo)",
            )
        }
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!owner.matches(SEGMENT) || !repo.matches(SEGMENT)) return null

        // Only releases/tags map to supported endpoints; other paths are not
        // silently broadened into a repository-wide source.
        when (segments.getOrNull(2)) {
            null, "releases" -> {}
            "tags" -> {}
            else -> throw com.mezo.trace.domain.ValidationException(
                "input",
                "only /releases or /tags endpoints are supported for github repositories",
            )
        }
        val kind = if (segments.getOrNull(2) == "tags") "tags" else "releases"

        return SourceProposal(
            inputUrl = rawUrlOf(normalizedUrl),
            normalizedUrl = normalizedUrl,
            provider = Provider.GITHUB,
            sourceType = SOURCE_TYPE_REPOSITORY,
            canonicalIdentity = "$owner/$repo",
            endpointUri = "github://$owner/$repo/$kind",
            displayUrl = "https://github.com/$owner/$repo/${if (kind == "releases") "releases" else "tags"}",
            endpointType = if (kind == "releases") ENDPOINT_RELEASES else ENDPOINT_TAGS,
            adapterId = ADAPTER_GITHUB,
            capabilities = setOf(
                Capability.RELEASES,
                Capability.CONDITIONAL_POLL,
                Capability.PAGINATED,
                Capability.RATE_LIMITED_API,
            ),
            trust = TrustClassification.OFFICIAL,
            displayName = "$owner/$repo",
            notes = listOf("exact endpoint: repository ${kind}"),
        )
    }

    // ---- Web sources: content sniffing + structured-alternative discovery ----

    private fun probeWebSource(normalizedUrl: String): SourceProposal {
        val response = try {
            fetcher.fetch(FetchRequest(url = normalizedUrl))
        } catch (failure: com.mezo.trace.application.port.FetchFailure) {
            throw failure
        }

        val contentType = response.contentType?.lowercase()?.substringBefore(';') ?: ""
        val head = response.body.decodeToString().take(SNIFF_LIMIT)
        val lowerHead = head.lowercase()

        // Direct feed responses win immediately.
        if (contentType.contains("rss+xml") || lowerHead.startsWith("<?xml") && RSS_TAG in lowerHead) {
            return feedProposal(normalizedUrl, normalizedUrl, format = FORMAT_RSS)
        }
        if (contentType.contains("atom+xml") || lowerHead.startsWith("<?xml") && FEED_TAG in lowerHead) {
            return feedProposal(normalizedUrl, normalizedUrl, format = FORMAT_ATOM)
        }

        // HTML: look for declared structured alternatives before degrading.
        discoverFeedLink(head, normalizedUrl)?.let { discovered ->
            val isAtom = discovered.second.contains("atom")
            return feedProposal(
                normalizedUrl = discovered.first,
                originalInput = discovered.first,
                format = if (isAtom) FORMAT_ATOM else FORMAT_RSS,
                note = "structured alternative discovered; confirming this feed replaces html scraping",
            )
        }

        // Degraded fallback: page monitoring with controlled HTML extraction
        // (JSON-LD first, then <article> markup — plan §8 steps 7–10).
        // Sitemap discovery runs BEFORE declaring the page degraded.
        val sitemapNote = sitemaps.discover(normalizedUrl)
            ?.let { listOf("sitemap_found:" + it.take(200)) }
            ?: emptyList()
        return SourceProposal(
            inputUrl = rawUrlOf(normalizedUrl),
            normalizedUrl = normalizedUrl,
            provider = Provider.WEB_PAGE,
            sourceType = SOURCE_TYPE_PAGE,
            canonicalIdentity = normalizedUrl,
            endpointUri = normalizedUrl,
            displayUrl = normalizedUrl,
            endpointType = ENDPOINT_PAGE,
            adapterId = ADAPTER_WEB_PAGE,
            capabilities = setOf(Capability.HTML_EXTRACT, Capability.CONDITIONAL_POLL),
            trust = TrustClassification.UNKNOWN,
            displayName = pageTitle(head) ?: hostOf(normalizedUrl),
            notes = listOfNotNull(
                "degraded source: no structured feed found; controlled HTML extraction applies",
            ) + sitemapNote,
        )
    }

    private fun feedProposal(
        normalizedUrl: String,
        originalInput: String,
        format: String,
        note: String? = null,
    ): SourceProposal = SourceProposal(
        inputUrl = rawUrlOf(originalInput),
        normalizedUrl = originalInput,
        provider = if (format == FORMAT_ATOM) Provider.ATOM else Provider.RSS,
        sourceType = SOURCE_TYPE_FEED,
        canonicalIdentity = normalizedUrl,
        endpointUri = normalizedUrl,
        displayUrl = normalizedUrl,
        endpointType = ENDPOINT_FEED,
        adapterId = if (format == FORMAT_ATOM) ADAPTER_ATOM else ADAPTER_RSS,
        capabilities = setOf(Capability.FEED_ENTRIES, Capability.CONDITIONAL_POLL),
        trust = TrustClassification.UNKNOWN,
        displayName = hostOf(originalInput),
        notes = listOfNotNull(note),
    )

    /**
     * Bounded discovery of `<link rel="alternate" type="application/rss+xml|atom+xml" href="…">`.
     * Discovery only — the resolved feed becomes a proposal, never auto-created.
     *
     * Attribute order varies in the wild, so each `<link>` tag is scanned for
     * its attributes rather than matched positionally. Relative hrefs resolve
     * against `<base href>` first, then the page's canonical URL, then the
     * fetched URL (HTML spec order).
     */
    private fun discoverFeedLink(head: String, baseUrl: String): Pair<String, String>? {
        val base = resolveBase(head, baseUrl) ?: return null
        return LINK_TAG_REGEX.findAll(head).firstNotNullOfOrNull { match ->
            val attrs = attributesOf(match.value)
            val rel = attrs["rel"]?.lowercase()?.split(REL_SPLIT)?.map { it.trim() }
                ?: return@firstNotNullOfOrNull null
            val type = attrs["type"]?.lowercase()?.substringBefore(';')?.trim()
                ?: return@firstNotNullOfOrNull null
            val href = attrs["href"]?.trim()
                ?: return@firstNotNullOfOrNull null
            if ("alternate" !in rel) return@firstNotNullOfOrNull null
            if (type !in FEED_TYPES) return@firstNotNullOfOrNull null
            if (href.isBlank()) return@firstNotNullOfOrNull null
            runCatching { base.resolve(href).toString() }
                .getOrNull()
                ?.let { it to type }
        }
    }

    /** `<base href>` → canonical → fetched URL, whichever parses first. */
    private fun resolveBase(head: String, baseUrl: String): java.net.URI? {
        BASE_REGEX.find(head)?.groupValues?.get(1)?.trim()?.take(200)
            ?.let { href ->
                runCatching { java.net.URI.create(baseUrl).resolve(href) }.getOrNull()?.let { return it }
            }
        CANONICAL_REGEX.find(head)?.groupValues?.get(1)?.trim()
            ?.let { href ->
                runCatching { java.net.URI.create(href) }.getOrNull()?.let { return it }
            }
        return runCatching { java.net.URI.create(baseUrl) }.getOrNull()
    }

    /** Attribute pairs of a single tag; lowercased names, verbatim values. */
    private fun attributesOf(tag: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(tag).forEach { match ->
            val name = match.groupValues[1].lowercase()
            if (attrs.containsKey(name)) return@forEach
            attrs[name] = match.groupValues[2]
        }
        return attrs
    }

    private fun pageTitle(head: String): String? =
        TITLE_REGEX.find(head)?.groupValues?.get(1)?.trim()?.take(100)?.ifEmpty { null }

    private fun hostOf(url: String): String = runCatching { java.net.URI.create(url).host }.getOrNull() ?: url

    private fun rawUrlOf(normalized: String): String = normalized

    companion object {
        private const val SNIFF_LIMIT = 64_000
        private const val RSS_TAG = "<rss"
        private const val FEED_TAG = "<feed"
        private const val FORMAT_RSS = "rss"
        private const val FORMAT_ATOM = "atom"
        private const val ADAPTER_GITHUB = "github"
        private const val ADAPTER_RSS = "rss"
        private const val ADAPTER_ATOM = "atom"
        private const val ADAPTER_WEB_PAGE = "web_page"
        const val SOURCE_TYPE_REPOSITORY = "REPOSITORY"
        const val SOURCE_TYPE_FEED = "FEED"
        const val SOURCE_TYPE_PAGE = "PAGE"
        const val ENDPOINT_RELEASES = "RELEASES"
        const val ENDPOINT_TAGS = "TAGS"
        const val ENDPOINT_FEED = "FEED"
        const val ENDPOINT_PAGE = "PAGE"

        private val SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")

        /** Feed link types we can actually ingest with a dedicated adapter. */
        private val FEED_TYPES = setOf("application/rss+xml", "application/atom+xml")

        private val REL_SPLIT = Regex("\\s+")

        private val LINK_TAG_REGEX = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

        /** `name="value"` / `name='value'` pairs (unquoted attributes are not specs-valid). */
        private val ATTR_REGEX = Regex(
            """([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*["']([^"']*)["']""",
        )

        private val BASE_REGEX = Regex(
            """<base[^>]+href=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )

        private val CANONICAL_REGEX = Regex(
            """<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )

        private val TITLE_REGEX = Regex(
            """<title[^>]*>(.*?)</title""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
