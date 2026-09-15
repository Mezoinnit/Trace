package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchedResponse
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.domain.Provider
import com.mezo.trace.domain.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class ResolveMapFetcher(private val byUrl: Map<String, Pair<String?, String>>) : HttpFetcher {
    override fun fetch(request: FetchRequest): FetchedResponse {
        val (contentType, body) = byUrl[request.url]
            ?: throw com.mezo.trace.application.port.FetchFailure(
                com.mezo.trace.application.port.FetchFailure.Kind.NETWORK,
                "no fixture for ${request.url}",
            )
        return FetchedResponse(request.url, 200, contentType, body.toByteArray(), null, null)
    }
}

class ResolveSourceTest {

    private val resolver = ResolveSource(
        ResolveMapFetcher(
            mapOf(
                "https://blog.example.com/" to (null to """
                    <html><head>
                      <title>Example Blog</title>
                      <link rel="alternate" type="application/rss+xml" href="/feed.xml"/>
                    </head><body>content</body></html>
                """.trimIndent()),
                "https://blog.example.com/feed.xml" to ("application/rss+xml" to "<rss version=\"2.0\"><channel/></rss>"),
                "https://plain.example.com/" to (null to "<html><head></head><body>No feeds here</body></html>"),
                "https://sitemap-site.example.com/" to (null to "<html><head><title>Has Sitemap</title></head><body>x</body></html>"),
                "https://sitemap-site.example.com/sitemap.xml" to
                    ("application/xml" to "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>"),
                "https://direct.example.com/feed" to ("application/atom+xml" to "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"/>"),
                "https://reordered.example.com/" to (null to """
                    <html><head>
                      <title>Reordered Attributes</title>
                      <link href="https://reordered.example.com/rss.xml" type="application/rss+xml" rel="alternate"/>
                    </head><body>content</body></html>
                """.trimIndent()),
                "https://based.example.com/" to (null to """
                    <html><head>
                      <base href="https://cdn.example.com/podcast/"/>
                      <link type="application/atom+xml" href="feed.xml" rel="alternate"/>
                    </head><body>content</body></html>
                """.trimIndent()),
                "https://ambiguous.example.com/" to (null to """
                    <html><head>
                      <link rel="alternate" type="application/xml" href="/sitemap.xml"/>
                    </head><body>content</body></html>
                """.trimIndent()),
            ),
        ),
    )

    @Test
    fun `github repository urls resolve to exact release endpoints`() {
        val proposal = resolver.execute("https://github.com/androidx/androidx/releases")

        assertEquals(Provider.GITHUB, proposal.provider)
        assertEquals("REPOSITORY", proposal.sourceType)
        assertEquals("androidx/androidx", proposal.canonicalIdentity)
        assertEquals("github://androidx/androidx/releases", proposal.endpointUri)
        assertEquals("RELEASES", proposal.endpointType)
        assertEquals("github", proposal.adapterId)
        assertTrue(proposal.capabilities.isNotEmpty())
    }

    @Test
    fun `unsupported github paths are rejected not broadened`() {
        assertFailsWith<ValidationException> {
            resolver.execute("https://github.com/androidx/androidx/blob/main/README.md")
        }
        assertFailsWith<ValidationException> { resolver.execute("https://github.com/androidx") }
    }

    @Test
    fun `direct feed responses are recognized by content type`() {
        val proposal = resolver.execute("https://direct.example.com/feed")
        assertEquals(Provider.ATOM, proposal.provider)
        assertEquals("FEED", proposal.sourceType)
        assertEquals("atom", proposal.adapterId)
        assertEquals("https://direct.example.com/feed", proposal.endpointUri)
    }

    @Test
    fun `html pages propose their declared structured alternative`() {
        val proposal = resolver.execute("https://blog.example.com/")
        assertEquals(Provider.RSS, proposal.provider)
        assertEquals("https://blog.example.com/feed.xml", proposal.endpointUri)
        assertTrue(proposal.notes.single().contains("structured alternative"))
    }

    @Test
    fun `pages without structure degrade explicitly`() {
        val proposal = resolver.execute("https://plain.example.com/")
        assertEquals(Provider.WEB_PAGE, proposal.provider)
        assertEquals("web_page", proposal.adapterId)
        assertTrue(proposal.notes.any { it.contains("degraded source") })
        assertTrue(proposal.capabilities.any { it.name == "CONDITIONAL_POLL" })
    }

    @Test
    fun `sitemap existence is noted on degraded page proposals`() {
        val proposal = resolver.execute("https://sitemap-site.example.com/")
        assertEquals(Provider.WEB_PAGE, proposal.provider)
        assertTrue(proposal.notes.any { it.startsWith("sitemap_found:") })
    }

    @Test
    fun `feed links are discovered regardless of attribute order`() {
        val proposal = resolver.execute("https://reordered.example.com/")
        assertEquals(Provider.RSS, proposal.provider)
        assertEquals("https://reordered.example.com/rss.xml", proposal.endpointUri)
        assertEquals("rss", proposal.adapterId)
    }

    @Test
    fun `relative feed hrefs resolve against base href`() {
        val proposal = resolver.execute("https://based.example.com/")
        assertEquals(Provider.ATOM, proposal.provider)
        assertEquals("https://cdn.example.com/podcast/feed.xml", proposal.endpointUri)
    }

    @Test
    fun `ambiguous xml alternates are not treated as feeds`() {
        val proposal = resolver.execute("https://ambiguous.example.com/")
        assertEquals(Provider.WEB_PAGE, proposal.provider)
        assertEquals("web_page", proposal.adapterId)
    }

    @Test
    fun `invalid urls are validation errors`() {
        assertFailsWith<ValidationException> { resolver.execute("   ") }
        assertFailsWith<ValidationException> { resolver.execute("ftp://example.com/x") }
        assertFailsWith<ValidationException> { resolver.execute("not a url at all") }
    }
}
