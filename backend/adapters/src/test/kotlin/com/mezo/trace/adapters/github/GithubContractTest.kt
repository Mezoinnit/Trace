package com.mezo.trace.adapters.github

import com.mezo.trace.adapters.AdapterContractTest
import com.mezo.trace.adapters.testing.StubHttpFetcher

class GithubContractTest : AdapterContractTest() {

    override val validEndpoint = "github://contract/owner/releases"
    override val invalidEndpoint = "https://example.com/feed.xml"
    override val fetchUrlPattern = Regex.escape(
        "https://api.github.com/repos/contract/owner/releases?per_page=30&page=1",
    )

    override fun createAdapter(fetcher: StubHttpFetcher) = GithubAdapter(fetcher)
}
