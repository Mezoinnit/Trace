package com.mezo.trace.adapters.feed

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.EndpointRejected
import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchResult
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.application.port.SourceAdapter
import org.w3c.dom.Document

/**
 * Shared fetch/normalize loop for RSS and Atom feeds. Only item extraction
 * differs between the two formats.
 */
abstract class FeedAdapter(
    private val fetcher: HttpFetcher,
) : SourceAdapter {

    protected fun fetchFeed(
        endpointUri: String,
        knownEtag: String?,
        extract: (Document) -> List<NormalizedItem>,
    ): FetchResult {
        val url = feedUrl(endpointUri)
            ?: throw EndpointRejected("not a $id feed endpoint: $endpointUri")

        val response = try {
            fetcher.fetch(FetchRequest(url = url, etag = knownEtag))
        } catch (failure: Exception) {
            throw failure
        }

        // Conditional poll hit: nothing changed since the stored ETag.
        if (response.statusCode == 304) {
            return FetchResult(items = emptyList(), etag = response.etag)
        }

        val document = SafeXml.parse(response.body)
        return FetchResult(items = extract(document).take(MAX_ITEMS_PER_CYCLE), etag = response.etag)
    }

    /** Feed endpoints are their own https/http URL. */
    protected fun feedUrl(endpointUri: String): String? =
        runCatching { AdapterSupport.normalizeUrl(endpointUri) }.getOrNull()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    protected fun preview(itemElement: org.w3c.dom.Element, vararg tags: String): String? =
        tags.firstNotNullOfOrNull { SafeXml.childText(itemElement, it) }
            ?.let { AdapterSupport.truncatePreview(it) }

    companion object {
        const val MAX_ITEMS_PER_CYCLE = 100
    }
}
