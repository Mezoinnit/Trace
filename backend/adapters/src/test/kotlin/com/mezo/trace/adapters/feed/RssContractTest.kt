package com.mezo.trace.adapters.feed

import com.mezo.trace.adapters.AdapterContractTest
import com.mezo.trace.adapters.testing.StubHttpFetcher

class RssContractTest : AdapterContractTest() {

    override val validEndpoint = "https://contract.example/rss.xml"
    override val invalidEndpoint = "github://no/pe/releases"
    override val fetchUrlPattern = Regex.escape("https://contract.example/rss.xml")

    override fun createAdapter(fetcher: StubHttpFetcher) = RssAdapter(fetcher)
}
