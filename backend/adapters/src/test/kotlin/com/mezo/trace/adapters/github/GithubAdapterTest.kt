package com.mezo.trace.adapters.github

import com.mezo.trace.adapters.testing.StubHttpFetcher
import com.mezo.trace.application.port.FetchFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GithubAdapterTest {

    private fun releasesJson(): String = """
        [
          {
            "id": 1001,
            "tag_name": "v1.9.0",
            "name": "Compose 1.9",
            "html_url": "https://github.com/androidx/androidx/releases/tag/v1.9.0",
            "published_at": "2026-08-01T10:00:00Z",
            "prerelease": false,
            "draft": false,
            "body": "<p>Stable release with <b>new features</b> and fixes.</p>"
          },
          {
            "id": 1002,
            "tag_name": "v1.9.0-rc01",
            "name": null,
            "html_url": "https://github.com/androidx/androidx/releases/tag/v1.9.0-rc01",
            "published_at": "2026-07-20T10:00:00Z",
            "prerelease": true,
            "draft": false,
            "body": "Release candidate"
          }
        ]
    """.trimIndent()

    private val fetcher = StubHttpFetcher(
        mutableMapOf(
            Regex.escape("https://api.github.com/repos/androidx/androidx/releases?per_page=30&page=1") to
                StubHttpFetcher.Response(body = releasesJson().toByteArray(), etag = "\"abc123\""),
        ),
    )

    @Test
    fun `fetches and normalizes releases`() {
        val adapter = GithubAdapter(fetcher)
        val result = adapter.fetch("github://androidx/androidx/releases", cursor = null, knownEtag = null)

        assertEquals(2, result.items.size)
        val first = result.items[0]
        assertEquals("1001", first.externalId)
        assertEquals("Compose 1.9", first.title)
        assertEquals("v1.9.0", first.metadata["tag"])
        assertEquals("false", first.metadata["prerelease"])
        assertNotNull(first.publishedAt)
        // HTML is stripped from the compact preview (plan §12).
        assertTrue(first.contentPreview!!.startsWith("Stable release"))
        assertTrue(!first.contentPreview!!.contains("<"))

        assertEquals("\"abc123\"", result.etag)

        // Second item falls back to tag name when the display name is null.
        assertEquals("v1.9.0-rc01", result.items[1].title)
    }

    @Test
    fun `conditional polling sends stored etag`() {
        val adapter = GithubAdapter(fetcher)
        adapter.fetch("github://androidx/androidx/releases", cursor = null, knownEtag = "\"stored\"")
        assertEquals("\"stored\"", fetcher.requestedEtags.single())
    }

    @Test
    fun `pagination cursor requests the next page`() {
        val adapter = GithubAdapter(fetcher)
        runCatching {
            adapter.fetch("github://androidx/androidx/releases", cursor = "2", knownEtag = null)
        }
        assertTrue(fetcher.requestedUrls.last().endsWith("page=2"))
    }

    @Test
    fun `rate limits surface as RATE_LIMITED failures`() {
        val limitedFetcher = StubHttpFetcher(
            mutableMapOf(
                Regex.escape("https://api.github.com/repos/o/r/releases?per_page=30&page=1") to
                    StubHttpFetcher.Response(statusCode = 429, body = ByteArray(0)),
            ),
        )
        val adapter = GithubAdapter(limitedFetcher, apiBase = "https://api.github.com")

        val failure = assertFailsWith<FetchFailure> {
            adapter.fetch("github://o/r/releases", cursor = null, knownEtag = null)
        }
        assertEquals(FetchFailure.Kind.RATE_LIMITED, failure.kind)
    }

    @Test
    fun `non-release kinds are not fetchable yet`() {
        val adapter = GithubAdapter(fetcher)
        org.junit.jupiter.api.assertThrows<com.mezo.trace.application.port.EndpointRejected> {
            adapter.fetch("github://o/r/tags", cursor = null, knownEtag = null)
        }
    }
}
