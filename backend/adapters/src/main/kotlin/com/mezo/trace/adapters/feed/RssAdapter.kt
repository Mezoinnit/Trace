package com.mezo.trace.adapters.feed

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider
import com.mezo.trace.adapters.feed.SafeXml.childText
import java.util.UUID

/**
 * RSS 2.0 adapter. Endpoint identity is the feed URL itself.
 * external_id: guid if present, else a stable hash of link+title (Rule 4).
 */
class RssAdapter(fetcher: HttpFetcher) : FeedAdapter(fetcher) {

    override val id: String = "rss"
    override val provider: Provider = Provider.RSS

    override fun supports(endpointUri: String): Boolean =
        feedUrl(endpointUri) != null && looksLikeFeedPath(endpointUri)

    override fun capabilitiesFor(endpointUri: String): Set<Capability> =
        setOf(Capability.FEED_ENTRIES, Capability.CONDITIONAL_POLL)

    override fun fetch(endpointUri: String, cursor: String?, knownEtag: String?) =
        fetchFeed(endpointUri, knownEtag) { document ->
            document.getElementsByTagName("item").let { items ->
                (0 until items.length).mapNotNull { i ->
                    val item = items.item(i) as? org.w3c.dom.Element ?: return@mapNotNull null
                    normalizeRssItem(item)
                }
            }
        }

    private fun normalizeRssItem(item: org.w3c.dom.Element): NormalizedItem? {
        val title = childText(item, "title")
        val link = childText(item, "link")
        val guid = childText(item, "guid")
        if (title == null && link == null) return null

        return NormalizedItem(
            externalId = guid ?: stableId(link, title),
            canonicalUrl = link,
            title = title,
            author = childText(item, "author") ?: childText(item, "{http://purl.org/dc/elements/1.1/}creator"),
            publishedAt = AdapterSupport.parseTimestamp(childText(item, "pubDate")),
            contentPreview = preview(item, "description", "content:encoded"),
            metadata = mapOf("format" to "rss"),
        )
    }

    private fun looksLikeFeedPath(url: String): Boolean {
        val path = runCatching { java.net.URI.create(url).path?.lowercase() }.getOrNull() ?: return true
        return true // any URL may serve RSS; content sniffing happens at parse time
    }

    private fun stableId(link: String?, title: String?): String =
        UUID.nameUUIDFromBytes("${link.orEmpty()}|${title.orEmpty()}".toByteArray()).toString()
}
