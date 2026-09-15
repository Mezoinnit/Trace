package com.mezo.trace.adapters.feed

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider
import com.mezo.trace.adapters.feed.SafeXml.childText
import java.util.UUID

/**
 * Atom adapter. Endpoint identity is the feed URL itself.
 * external_id: atom:id if present, else a stable hash of link+title.
 */
class AtomAdapter(fetcher: HttpFetcher) : FeedAdapter(fetcher) {

    override val id: String = "atom"
    override val provider: Provider = Provider.ATOM

    override fun supports(endpointUri: String): Boolean = feedUrl(endpointUri) != null

    override fun capabilitiesFor(endpointUri: String): Set<Capability> =
        setOf(Capability.FEED_ENTRIES, Capability.CONDITIONAL_POLL)

    override fun fetch(endpointUri: String, cursor: String?, knownEtag: String?) =
        fetchFeed(endpointUri, knownEtag) { document ->
            document.getElementsByTagName("entry").let { entries ->
                (0 until entries.length).mapNotNull { i ->
                    val entry = entries.item(i) as? org.w3c.dom.Element ?: return@mapNotNull null
                    normalizeEntry(entry)
                }
            }
        }

    private fun normalizeEntry(entry: org.w3c.dom.Element): NormalizedItem? {
        val title = childText(entry, "title")
        val link = linkHref(entry)
        val atomId = childText(entry, "id")
        if (title == null && link == null) return null

        return NormalizedItem(
            externalId = atomId ?: stableId(link, title),
            canonicalUrl = link,
            title = title,
            author = entry.getElementsByTagName("author").let { authors ->
                (0 until authors.length).firstNotNullOfOrNull { a ->
                    (authors.item(a) as? org.w3c.dom.Element)?.let { childText(it, "name") }
                }
            },
            publishedAt = AdapterSupport.parseTimestamp(childText(entry, "published"))
                ?: AdapterSupport.parseTimestamp(childText(entry, "updated")),
            contentPreview = preview(entry, "summary", "content"),
            metadata = mapOf(
                "format" to "atom",
                "provider" to Provider.ATOM.name,
            ),
        )
    }

    /** Prefers rel="alternate"; falls back to the first link. */
    private fun linkHref(entry: org.w3c.dom.Element): String? {
        val links = entry.getElementsByTagName("link")
        var first: String? = null
        for (i in 0 until links.length) {
            val el = links.item(i) as? org.w3c.dom.Element ?: continue
            val href = el.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
            if (el.getAttribute("rel") == "alternate" || el.getAttribute("rel").isEmpty()) {
                return href
            }
            if (first == null) first = href
        }
        return first
    }

    private fun stableId(link: String?, title: String?): String =
        UUID.nameUUIDFromBytes("${link.orEmpty()}|${title.orEmpty()}".toByteArray()).toString()
}
