package com.mezo.trace.adapters

import com.mezo.trace.adapters.feed.AtomAdapter
import com.mezo.trace.adapters.feed.RssAdapter
import com.mezo.trace.adapters.github.GithubAdapter
import com.mezo.trace.adapters.web.WebPageAdapter
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.SourceAdapter

/**
 * Adapter registry — provider-driven selection (ADR-005). New adapters
 * register here; the resolver and pipeline never reference concrete classes.
 * Implements the application-layer [AdapterSelector] port.
 */
class AdapterRegistry(fetcher: HttpFetcher) : com.mezo.trace.application.port.AdapterSelector {

    private val github = GithubAdapter(fetcher)
    private val rss = RssAdapter(fetcher)
    private val atom = AtomAdapter(fetcher)
    private val webPage = WebPageAdapter(fetcher)

    val all: List<SourceAdapter> = listOf(github, rss, atom, webPage)

    override fun byId(id: String): SourceAdapter? = all.firstOrNull { it.id == id }

    override fun forUri(endpointUri: String): SourceAdapter? =
        when {
            endpointUri.startsWith("github://") -> github.takeIf { it.supports(endpointUri) }
            else -> all.firstOrNull { it != github && it.supports(endpointUri) }
        }
}
