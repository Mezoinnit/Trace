package com.mezo.trace.adapters.feed

import com.mezo.trace.adapters.StubFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeedAdaptersTest {

    private val rssFixture = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>Example Blog</title>
          <item>
            <title>First post</title>
            <link>https://blog.example.com/first</link>
            <guid isPermaLink="false">post-1</guid>
            <pubDate>Fri, 21 Aug 2026 09:00:00 GMT</pubDate>
            <description><![CDATA[Hello <b>world</b> from the first post.]]></description>
          </item>
          <item>
            <title>Second post</title>
            <link>https://blog.example.com/second</link>
            <pubDate>Thu, 20 Aug 2026 08:00:00 GMT</pubDate>
            <description>Plain text body</description>
          </item>
        </channel></rss>
    """.trimIndent()

    private val atomFixture = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Atom Example</title>
          <entry>
            <id>urn:uuid:aaa-bbb</id>
            <title>Atom entry one</title>
            <link rel="alternate" href="https://feeds.example.com/one"/>
            <published>2026-08-19T12:00:00Z</published>
            <author><name>Ada</name></author>
            <summary>Summary text</summary>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `rss items normalize with stable external ids`() {
        val fetcher = StubFetcher(mutableMapOf("https://blog.example.com/feed.xml" to StubFetcher.Response(body = rssFixture.toByteArray())))
        val adapter = RssAdapter(fetcher)

        val result = adapter.fetch("https://blog.example.com/feed.xml", cursor = null, knownEtag = null)

        assertEquals(2, result.items.size)
        assertEquals("post-1", result.items[0].externalId)
        assertEquals("https://blog.example.com/first", result.items[0].canonicalUrl)
        assertNotNull(result.items[0].publishedAt)
        // guid absent → deterministic derived id (stable across polls, Rule 4).
        val second = result.items[1]
        assertEquals(
            second.externalId,
            java.util.UUID.nameUUIDFromBytes("https://blog.example.com/second|Second post".toByteArray()).toString(),
        )
        // Tags stripped from previews.
        assertTrue(result.items[0].contentPreview!!.contains("Hello world"))
        assertTrue(!result.items[0].contentPreview!!.contains("<b>"))
    }

    @Test
    fun `atom entries prefer atom ids and alternate links`() {
        val fetcher = StubFetcher(mutableMapOf("https://feeds.example.com/atom.xml" to StubFetcher.Response(body = atomFixture.toByteArray())))
        val adapter = AtomAdapter(fetcher)

        val result = adapter.fetch("https://feeds.example.com/atom.xml", cursor = null, knownEtag = null)

        assertEquals(1, result.items.size)
        assertEquals("urn:uuid:aaa-bbb", result.items[0].externalId)
        assertEquals("https://feeds.example.com/one", result.items[0].canonicalUrl)
        assertEquals("Ada", result.items[0].author)
        assertNotNull(result.items[0].publishedAt)
    }

    @Test
    fun `304 not-modified returns no items and keeps the etag`() {
        val fetcher = StubFetcher(
            mutableMapOf("https://blog.example.com/feed.xml" to StubFetcher.Response(statusCode = 304, body = ByteArray(0), etag = "\"same\"")),
        )
        val adapter = RssAdapter(fetcher)
        val result = adapter.fetch("https://blog.example.com/feed.xml", cursor = null, knownEtag = "\"same\"")

        assertEquals(0, result.items.size)
        assertEquals("\"same\"", result.etag)
        assertTrue(fetcher.requestedEtags.single() == "\"same\"")
    }

    @Test
    fun `xxe payloads are rejected by the hardened parser`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel>&x;</channel></rss>
        """.trimIndent()
        val fetcher = StubFetcher(mutableMapOf("https://evil.example/feed.xml" to StubFetcher.Response(body = xxe.toByteArray())))
        val adapter = RssAdapter(fetcher)

        org.junit.jupiter.api.assertThrows<com.mezo.trace.application.port.FetchFailure> {
            adapter.fetch("https://evil.example/feed.xml", cursor = null, knownEtag = null)
        }
    }
}
