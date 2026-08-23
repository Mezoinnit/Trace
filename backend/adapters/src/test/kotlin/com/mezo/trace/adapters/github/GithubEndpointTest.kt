package com.mezo.trace.adapters.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GithubEndpointTest {

    @Test
    fun `parses repository urls to the releases endpoint`() {
        val endpoint = GithubEndpoint.fromUrl("https://github.com/androidx/androidx/releases")
        assertNotNull(endpoint)
        assertEquals("androidx", endpoint.owner)
        assertEquals("androidx", endpoint.repo)
        assertEquals("releases", endpoint.kind)
        assertEquals("github://androidx/androidx/releases", endpoint.uri)
    }

    @Test
    fun `bare repository urls map to releases without broadening`() {
        val endpoint = GithubEndpoint.fromUrl("https://github.com/JetBrains/kotlin")
        assertNotNull(endpoint)
        assertEquals("github://JetBrains/kotlin/releases", endpoint.uri)
    }

    @Test
    fun `tags pages map to tags endpoints`() {
        val endpoint = GithubEndpoint.fromUrl("https://github.com/JetBrains/kotlin/tags")
        assertNotNull(endpoint)
        assertEquals("github://JetBrains/kotlin/tags", endpoint.uri)
    }

    @Test
    fun `non-github hosts are not recognized`() {
        assertNull(GithubEndpoint.fromUrl("https://gitlab.com/jetbrains/kotlin"))
        assertNull(GithubEndpoint.fromUrl("https://example.com/androidx/androidx/releases"))
    }

    @Test
    fun `unsupported subpaths are rejected instead of broadened`() {
        // /blob/main/README.md is not a monitored endpoint.
        assertNull(GithubEndpoint.fromUrl("https://github.com/o/r/blob/main/README.md"))
    }

    @Test
    fun `malformed endpoints are rejected on parse`() {
        listOf(
            "github://owner",
            "github://owner/",
            "github:///repo/releases",
            "github://own er/repo/releases",
            "github://owner/repo/wiki",
            "https://github.com/o/r",
        ).forEach { uri ->
            org.junit.jupiter.api.assertThrows<com.mezo.trace.application.port.EndpointRejected> {
                GithubEndpoint.parse(uri)
            }
        }
    }
}
