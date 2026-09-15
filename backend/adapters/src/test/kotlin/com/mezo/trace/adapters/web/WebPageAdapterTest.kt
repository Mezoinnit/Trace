package com.mezo.trace.adapters.web

import com.mezo.trace.adapters.testing.StubHttpFetcher
import com.mezo.trace.application.port.FetchFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebPageAdapterTest {

    private val pageUrl = "https://site.example.com/news"

    private fun adapter(fetcher: StubHttpFetcher) = WebPageAdapter(fetcher)

    private fun servePage(fetcher: StubHttpFetcher, body: String, statusCode: Int = 200) {
        fetcher.serve(pageUrl, StubHttpFetcher.Response(contentType = "text/html", body = body.toByteArray(), statusCode = statusCode))
    }

    private fun serveRobots(fetcher: StubHttpFetcher, body: String, statusCode: Int = 200) {
        fetcher.serve("https://site.example.com/robots.txt", StubHttpFetcher.Response(contentType = "text/plain", body = body.toByteArray(), statusCode = statusCode))
    }

    @Test
    fun `web and http endpoints are supported`() {
        val fetcher = StubHttpFetcher()
        assertTrue(adapter(fetcher).supports("https://site.example.com/"))
        assertTrue(adapter(fetcher).supports("http://site.example.com/"))
        assertTrue(adapter(fetcher).supports("ftp://site.example.com/").not())
    }

    @Test
    fun `robots url is fetched from the origin before the page`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "User-agent: *\nAllow: /")
        servePage(fetcher, "<html><body><article><h1>Post</h1></article></body></html>")

        adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)

        assertEquals(
            listOf("https://site.example.com/robots.txt", pageUrl),
            fetcher.requestedUrls,
        )
    }

    @Test
    fun `robots disallowed returns empty result with a visible note`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "User-agent: *\nDisallow: /news")
        servePage(fetcher, "<html><body>ignored</body></html>")

        val result = adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)

        assertEquals(0, result.items.size)
        assertEquals(listOf("robots_disallowed"), result.notes)
    }

    @Test
    fun `robots failures are ignored politely`() {
        val fetcher = StubHttpFetcher()
        servePage(fetcher, "<html><body><article><h1>Reachable</h1></article></body></html>")

        val result = adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)

        assertEquals(1, result.items.size)
    }

    @Test
    fun `json-ld page items are normalized`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        servePage(
            fetcher,
            """
            <html><head><link rel="canonical" href="https://site.example.com/news"/></head><body>
              <script type="application/ld+json">
                {"@type":"Article","headline":"Big news","url":"https://site.example.com/news/big"}
              </script>
            </body></html>
            """.trimIndent(),
        )

        val result = adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)

        assertEquals(1, result.items.size)
        assertEquals("Big news", result.items.single().title)
        assertEquals("https://site.example.com/news/big", result.items.single().canonicalUrl)
    }

    @Test
    fun `304 returns empty items and preserves the etag`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        fetcher.serve(
            pageUrl,
            StubHttpFetcher.Response(statusCode = 304, contentType = "text/html", body = ByteArray(0), etag = "\"v1\""),
        )

        val result = adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = "\"v1\"")

        assertEquals(0, result.items.size)
        assertEquals("\"v1\"", result.etag)
        assertEquals(listOf("not_modified"), result.notes)
    }

    @Test
    fun `extracted items carry WEB_PAGE provider for event identity`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        servePage(
            fetcher,
            """
            <html><body>
              <script type="application/ld+json">
                {"@type":"Article","headline":"Big news","url":"https://site.example.com/news/big"}
              </script>
            </body></html>
            """.trimIndent(),
        )

        val item = adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null).items.single()
        assertEquals("WEB_PAGE", item.metadata["provider"])
    }

    @Test
    fun `429 maps to RATE_LIMITED failure`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        servePage(fetcher, "", statusCode = 429)

        val failure = assertFailsWith<FetchFailure> {
            adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)
        }
        assertEquals(FetchFailure.Kind.RATE_LIMITED, failure.kind)
    }

    @Test
    fun `403 maps to AUTH_REQUIRED failure`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        servePage(fetcher, "", statusCode = 403)

        val failure = assertFailsWith<FetchFailure> {
            adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)
        }
        assertEquals(FetchFailure.Kind.AUTH_REQUIRED, failure.kind)
    }

    @Test
    fun `500 maps to NETWORK failure`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        servePage(fetcher, "", statusCode = 500)

        val failure = assertFailsWith<FetchFailure> {
            adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)
        }
        assertEquals(FetchFailure.Kind.NETWORK, failure.kind)
    }

    @Test
    fun `invalid endpoint uris are rejected`() {
        val fetcher = StubHttpFetcher()
        assertFailsWith<com.mezo.trace.application.port.EndpointRejected> {
            adapter(fetcher).fetch("not a url", cursor = null, knownEtag = null)
        }
    }

    @Test
    fun `extraction notes are surfaced for parser visibility`() {
        val fetcher = StubHttpFetcher()
        serveRobots(fetcher, "")
        servePage(fetcher, "<html><body>nothing extractable</body></html>")

        val result = adapter(fetcher).fetch(pageUrl, cursor = null, knownEtag = null)

        assertTrue(result.notes.contains("no_extractable_article_content"))
    }
}
