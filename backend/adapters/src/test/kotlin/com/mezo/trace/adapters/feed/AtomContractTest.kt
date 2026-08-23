package com.mezo.trace.adapters.feed

import com.mezo.trace.adapters.AdapterContractTest
import com.mezo.trace.adapters.testing.StubHttpFetcher

class AtomContractTest : AdapterContractTest() {

    override val validEndpoint = "https://contract.example/atom.xml"
    override val invalidEndpoint = "github://no/pe/releases"
    override val fetchUrlPattern = Regex.escape("https://contract.example/atom.xml")

    override fun createAdapter(fetcher: StubHttpFetcher) = AtomAdapter(fetcher)
}
