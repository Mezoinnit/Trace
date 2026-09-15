package com.mezo.trace.adapters.web

import com.mezo.trace.adapters.testing.StubHttpFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlExtractorTest {

    private val fetcher = StubHttpFetcher()
    private val extractor = HtmlExtractor

    @Test
    fun `json-ld articles are extracted with full provenance`() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://news.example.com/tech"/>
              <title>Tech News</title>
            </head><body>
              <script type="application/ld+json">
                {"@type":"NewsArticle","headline":"Robots learn to juggle",
                 "url":"https://news.example.com/robots-juggle",
                 "author":"A. Author","datePublished":"2026-09-01T10:00:00Z",
                 "description":"A breakthrough in juggling robots."}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://news.example.com/tech")

        assertEquals("https://news.example.com/tech", result.canonicalUrl)
        assertEquals("Tech News", result.title)
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("https://news.example.com/robots-juggle", item.externalId)
        assertEquals("Robots learn to juggle", item.title)
        assertEquals("A. Author", item.author)
        assertEquals("json-ld", item.metadata["format"])
        assertEquals("WEB_PAGE", item.metadata["provider"])
        assertTrue(result.notes.single().startsWith("json_ld_articles:"))
    }

    @Test
    fun `json-ld graph arrays yield article items`() {
        val html = """
            <html><body>
              <script type="application/ld+json">
                {"@graph":[
                  {"@type":"Organization","name":"Corp"},
                  {"@type":"BlogPosting","headline":"From graph","url":"https://g.example.com/1"}
                ]}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://g.example.com/")
        assertEquals(1, result.items.size)
        assertEquals("From graph", result.items.single().title)
    }

    @Test
    fun `open graph is used when json-ld and articles are absent`() {
        val html = """
            <html><head>
              <meta property="og:title" content="OG Headline"/>
              <meta property="og:url" content="https://og.example.com/story"/>
              <meta property="og:description" content="A short blurb."/>
              <meta name="description" content="ignored when og present"/>
            </head><body><p>no articles</p></body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://og.example.com/")
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("OG Headline", item.title)
        assertEquals("https://og.example.com/story", item.externalId)
        assertEquals("open-graph", item.metadata["format"])
        assertEquals("WEB_PAGE", item.metadata["provider"])
        assertTrue(result.notes.contains("open_graph"))
    }

    @Test
    fun `article tags are used when json-ld is absent`() {
        val html = """
            <html><body>
              <article>
                <h1>First Post</h1>
                <p>Body text.</p>
                <a href="/posts/first">read more</a>
              </article>
              <article>
                <h2>Second Post</h2>
                <a href="https://other.example.com/posts/second">read</a>
              </article>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://blog.example.com/")

        assertEquals(2, result.items.size)
        assertEquals("First Post", result.items[0].title)
        assertEquals("https://blog.example.com/posts/first", result.items[0].canonicalUrl)
        assertEquals("https://other.example.com/posts/second", result.items[1].canonicalUrl)
        assertEquals("html-article", result.items[0].metadata["format"])
    }

    @Test
    fun `json-ld wins over article tags to avoid double counting`() {
        val html = """
            <html><body>
              <script type="application/ld+json">
                {"@type":"BlogPosting","headline":"Only once","url":"https://b.example.com/1"}
              </script>
              <article><h1>Only once</h1></article>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://b.example.com/")
        assertEquals(1, result.items.size)
        assertEquals("json-ld", result.items.single().metadata["format"])
    }

    @Test
    fun `non-article json-ld types are ignored`() {
        val html = """
            <html><body>
              <script type="application/ld+json">
                {"@type":"Organization","name":"Example Corp"}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://corp.example.com/")
        assertTrue(result.items.isEmpty())
        assertTrue(result.notes.contains("no_extractable_article_content"))
    }

    @Test
    fun `malformed json-ld never breaks extraction`() {
        val html = """
            <html><body>
              <script type="application/ld+json">{not valid json!!}
              </script>
              <article><h1>Fallback still works</h1></article>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html.toByteArray(), "https://b.example.com/")
        assertEquals(1, result.items.size)
        assertEquals("Fallback still works", result.items.single().title)
    }

    @Test
    fun `empty pages are reported as no extractable content`() {
        val result = extractor.extract("<html><body></body></html>".toByteArray(), "https://x.example.com/")
        assertTrue(result.items.isEmpty())
        assertTrue(result.notes.contains("no_extractable_article_content"))
    }

    @Test
    fun `article cap is bounded (Rule 6)`() {
        val articles = (1..(HtmlExtractor.MAX_ARTICLES + 20)).joinToString("") { i ->
            "<article><h2>Post $i</h2></article>"
        }
        val html = "<html><body>$articles</body></html>"

        val result = extractor.extract(html.toByteArray(), "https://b.example.com/")
        assertEquals(HtmlExtractor.MAX_ARTICLES, result.items.size)
    }
}
