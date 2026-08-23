package com.mezo.trace.adapters

import com.mezo.trace.adapters.testing.StubHttpFetcher
import com.mezo.trace.application.port.EndpointRejected
import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.application.port.SourceAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The common fixture-based contract every adapter must satisfy
 * (SOURCE_ADAPTERS.md). Concrete adapters supply micro-fixtures; the base
 * asserts the shared behavior matrix:
 *
 * valid endpoint / invalid endpoint / malformed payload / 304 / 429
 */
abstract class AdapterContractTest {

    protected lateinit var fetcher: StubHttpFetcher
    protected lateinit var adapter: SourceAdapter

    /** An endpoint this adapter must accept. */
    protected abstract val validEndpoint: String

    /** An endpoint this adapter must reject (wrong shape for the provider). */
    protected abstract val invalidEndpoint: String

    /** URL pattern (regex-escaped) the adapter will fetch for [validEndpoint]. */
    protected abstract val fetchUrlPattern: String

    /** Builds a fresh adapter bound to the given transport. */
    protected abstract fun createAdapter(fetcher: StubHttpFetcher): SourceAdapter

    @BeforeTest
    fun resetTransport() {
        fetcher = StubHttpFetcher()
        adapter = createAdapter(fetcher)
    }

    protected fun serve(body: ByteArray, statusCode: Int = 200, etag: String? = null) {
        fetcher.serve(fetchUrlPattern, StubHttpFetcher.Response(statusCode = statusCode, body = body, etag = etag))
    }

    @Test
    fun `contract - valid endpoint is supported`() {
        assertTrue(adapter.supports(validEndpoint), "${adapter.id} must support $validEndpoint")
    }

    @Test
    fun `contract - invalid endpoint is rejected without fetching`() {
        assertFalse(adapter.supports(invalidEndpoint), "$invalidEndpoint must not be supported")
        assertFailsWith<EndpointRejected>("$invalidEndpoint must be rejected") {
            adapter.fetch(invalidEndpoint, cursor = null, knownEtag = null)
        }
    }

    @Test
    fun `contract - malformed payload fails loudly, never silently empty`() {
        serve("<not-the-expected-format>>>".toByteArray())
        // Either the transport-level FetchFailure or the adapter's
        // EndpointRejected is acceptable; silence is not.
        assertFailsWith<RuntimeException> {
            adapter.fetch(validEndpoint, cursor = null, knownEtag = null)
        }
    }

    @Test
    fun `contract - 304 returns empty items and preserves the etag`() {
        serve(ByteArray(0), statusCode = 304, etag = "\"keep-me\"")
        val result = adapter.fetch(validEndpoint, cursor = null, knownEtag = "\"stored\"")
        assertEquals(0, result.items.size)
        assertEquals("\"keep-me\"", result.etag)
    }

    @Test
    fun `contract - 429 maps to RATE_LIMITED failure kind`() {
        serve(ByteArray(0), statusCode = 429)
        val failure = assertFailsWith<FetchFailure> {
            adapter.fetch(validEndpoint, cursor = null, knownEtag = null)
        }
        assertEquals(FetchFailure.Kind.RATE_LIMITED, failure.kind)
    }
}
