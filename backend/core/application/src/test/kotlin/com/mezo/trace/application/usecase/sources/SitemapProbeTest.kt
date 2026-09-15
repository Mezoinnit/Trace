package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchedResponse
import com.mezo.trace.application.port.HttpFetcher
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class SitemapMapFetcher(private val byUrl: Map<String, Pair<Int, ByteArray>>) : HttpFetcher {
    override fun fetch(request: FetchRequest): FetchedResponse {
        val (status, body) = byUrl[request.url]
            ?: throw com.mezo.trace.application.port.FetchFailure(
                com.mezo.trace.application.port.FetchFailure.Kind.NETWORK,
                "no fixture for ${request.url}",
            )
        return FetchedResponse(request.url, status, "application/xml", body, null, null)
    }
}

class SitemapProbeTest {

    private val origin = "https://site.example.com/"

    @Test
    fun `plain sitemap xml is discovered`() {
        val fetcher = SitemapMapFetcher(
            mapOf(
                "${origin}sitemap.xml" to (200 to "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>".toByteArray()),
            ),
        )
        assertEquals("${origin}sitemap.xml", SitemapProbe(fetcher).discover(origin))
    }

    @Test
    fun `xml declaration before the root element still matches`() {
        val fetcher = SitemapMapFetcher(
            mapOf(
                "${origin}sitemap.xml" to (200 to "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></sitemapindex>".toByteArray()),
            ),
        )
        assertEquals("${origin}sitemap.xml", SitemapProbe(fetcher).discover(origin))
    }

    @Test
    fun `gzipped sitemaps are decompressed before matching`() {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gzip ->
                gzip.write("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>".toByteArray())
            }
        }.toByteArray()
        val fetcher = SitemapMapFetcher(
            mapOf(
                "${origin}sitemap.xml" to (404 to ByteArray(0)),
                "${origin}sitemap_index.xml.gz" to (200 to bytes),
            ),
        )
        assertEquals("${origin}sitemap_index.xml.gz", SitemapProbe(fetcher).discover(origin))
    }

    @Test
    fun `candidates are probed in order until one matches`() {
        val fetcher = SitemapMapFetcher(
            mapOf(
                "${origin}sitemap.xml" to (404 to ByteArray(0)),
                "${origin}sitemap_index.xml" to (200 to "<sitemapindex></sitemapindex>".toByteArray()),
            ),
        )
        assertEquals("${origin}sitemap_index.xml", SitemapProbe(fetcher).discover(origin))
    }

    @Test
    fun `html served at a sitemap path is not a sitemap`() {
        val fetcher = SitemapMapFetcher(
            mapOf(
                "${origin}sitemap.xml" to (200 to "<html><body>not a sitemap</body></html>".toByteArray()),
            ),
        )
        assertNull(SitemapProbe(fetcher).discover(origin))
    }

    @Test
    fun `no sitemap anywhere returns null`() {
        val fetcher = SitemapMapFetcher(emptyMap())
        assertNull(SitemapProbe(fetcher).discover(origin))
    }

    @Test
    fun `invalid urls return null without probing`() {
        val fetcher = SitemapMapFetcher(emptyMap())
        assertNull(SitemapProbe(fetcher).discover("not a url"))
    }

    @Test
    fun `robots sitemap directive is preferred over well-known paths`() {
        val custom = "https://cdn.example.com/custom-sitemap.xml"
        val fetcher = SitemapMapFetcher(
            mapOf(
                "${origin}robots.txt" to
                    (200 to "User-agent: *\nSitemap: $custom\n".toByteArray()),
                custom to (200 to "<urlset></urlset>".toByteArray()),
                "${origin}sitemap.xml" to (200 to "<urlset></urlset>".toByteArray()),
            ),
        )
        assertEquals(custom, SitemapProbe(fetcher).discover(origin))
    }
}
