package com.mezo.trace.adapters.web

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.EndpointRejected
import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchResult
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.SourceAdapter
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider

/**
 * Generic web page adapter — the **degraded** fallback (plan §8, Rule 8).
 *
 * Phase 2 scope: health probing and metadata discovery only. Arbitrary HTML
 * extraction is intentionally NOT implemented here; structured alternatives
 * found during discovery must be confirmed by the user instead (resolver).
 * Extraction arrives in Phase 13 behind the same contract.
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
        setOf(Capability.HTML_EXTRACT)

    override fun fetch(endpointUri: String, cursor: String?, knownEtag: String?): FetchResult {
        val url = runCatching { AdapterSupport.normalizeUrl(endpointUri) }.getOrNull()
            ?: throw EndpointRejected("invalid web endpoint")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw EndpointRejected("not a web endpoint: $url")
        }

        val response = try {
            fetcher.fetch(FetchRequest(url = url, etag = knownEtag, maxBytes = maxBytes))
        } catch (failure: Exception) {
            throw failure
        }

        // Health probe only; no items extracted in this degraded phase.
        return FetchResult(items = emptyList(), etag = response.etag)
    }
}
