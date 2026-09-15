package com.mezo.trace.adapters.web

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.EndpointRejected
import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchResult
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.SourceAdapter
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider

/**
 * Generic web page adapter (SOURCE_ADAPTERS.md, plan §8). Honestly
 * degraded relative to structured sources, but produces real items from
 * JSON-LD and `<article>` markup, with robots.txt honor before any fetch.
 */
class WebPageAdapter(
    private val fetcher: HttpFetcher,
    private val maxBytes: Long = 1L * 1024 * 1024,
) : SourceAdapter {

    override val id: String = "web_page"
    override val provider: Provider = Provider.WEB_PAGE

    override fun supports(endpointUri: String): Boolean =
        runCatching { AdapterSupport.normalizeUrl(endpointUri) }.getOrNull()
            ?.startsWith("http") == true

    override fun capabilitiesFor(endpointUri: String): Set<Capability> =
        setOf(Capability.HTML_EXTRACT, Capability.CONDITIONAL_POLL)

    override fun fetch(endpointUri: String, cursor: String?, knownEtag: String?): FetchResult {
        val normalized = runCatching { AdapterSupport.normalizeUrl(endpointUri) }.getOrNull()
            ?: throw EndpointRejected("not a web endpoint")
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            throw EndpointRejected("not a web endpoint: $normalized")
        }

        // robots.txt honored before any page fetch (plan §8 step 3).
        val path = pathOf(normalized)
        val robotsUrl = originOf(normalized)?.let { "$it/robots.txt" }
            ?: return FetchResult(emptyList(), notes = listOf("robots_unparseable_url"))
        val robots = try {
            fetcher.fetch(
                FetchRequest(
                    url = robotsUrl,
                    maxBytes = 64 * 1024,
                ),
            )
        } catch (_: Exception) {
            null // unreachable robots.txt: proceed politely
        }
        if (robots != null && robots.statusCode == 200) {
            if (!RobotsParser.isAllowed(robots.body.decodeToString(), path)) {
                // Politely refused by robots policy (plan §8 step 3).
                return FetchResult(
                    items = emptyList(),
                    etag = null,
                    notes = listOf("robots_disallowed"),
                )
            }
        }

        val response = fetcher.fetch(FetchRequest(url = normalized, etag = knownEtag, maxBytes = maxBytes))
        if (response.statusCode == 304) {
            return FetchResult(emptyList(), etag = response.etag, notes = listOf("not_modified"))
        }
        if (response.statusCode !in 200..299) {
            throw FetchFailure(
                when (response.statusCode) {
                    429 -> FetchFailure.Kind.RATE_LIMITED
                    401, 403 -> FetchFailure.Kind.AUTH_REQUIRED
                    else -> FetchFailure.Kind.NETWORK
                },
                "web page fetch returned HTTP ${response.statusCode}",
            )
        }

        val extracted = HtmlExtractor.extract(response.body, response.finalUrl)
        return FetchResult(
            items = extracted.items,
            nextCursor = null,
            etag = response.etag,
            notes = extracted.notes,
        )
    }

    private fun pathOf(url: String): String = runCatching {
        java.net.URI.create(url).path ?: "/"
    }.getOrDefault("/")

    /** scheme://host[:port] with a trailing slash — the fetch origin. */
    private fun originOf(url: String): String? = runCatching {
        val uri = java.net.URI.create(url)
        val host = uri.host ?: return@runCatching null
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" } ?: ""
        "${uri.scheme}://$host$port"
    }.getOrNull()
}
