package com.mezo.trace.adapters.github

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.EndpointRejected
import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchResult
import com.mezo.trace.application.port.HttpFetcher
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.application.port.SourceAdapter
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider

/**
 * GitHub releases adapter (Phase 4 vertical-slice groundwork).
 *
 * Fetches the unauthenticated REST API for one repository's releases and
 * normalizes each release into a [NormalizedItem] keyed by the provider's
 * release id — the idempotency key `source_id + external_id` (Rule 4) is
 * therefore stable across polls.
 */
class GithubAdapter(
    private val fetcher: HttpFetcher,
    private val apiBase: String = "https://api.github.com",
    private val maxItemsPerCycle: Int = 30,
) : SourceAdapter {

    override val id: String = "github"
    override val provider: Provider = Provider.GITHUB

    override fun supports(endpointUri: String): Boolean =
        runCatching { GithubEndpoint.parse(endpointUri) }.isSuccess

    override fun capabilitiesFor(endpointUri: String): Set<Capability> =
        AdapterSupport.capabilities(
            Capability.RELEASES,
            Capability.CONDITIONAL_POLL,
            Capability.PAGINATED,
            Capability.RATE_LIMITED_API,
        )

    override fun fetch(endpointUri: String, cursor: String?, knownEtag: String?): FetchResult {
        val endpoint = GithubEndpoint.parse(endpointUri)
        if (endpoint.kind != "releases") {
            throw EndpointRejected("kind '${endpoint.kind}' not fetchable yet")
        }

        val page = cursor?.toIntOrNull()?.coerceIn(1, MAX_PAGE) ?: 1
        val url = "$apiBase/repos/${endpoint.owner}/${endpoint.repo}/releases?per_page=$maxItemsPerCycle&page=$page"

        val response = try {
            fetcher.fetch(FetchRequest(url = url, etag = knownEtag))
        } catch (failure: FetchFailure) {
            throw failure
        }

        // Status handling here too: adapters may run over any transport.
        when (response.statusCode) {
            304 -> return FetchResult(items = emptyList(), etag = response.etag)
            429 -> throw FetchFailure(FetchFailure.Kind.RATE_LIMITED, "github rate limit reached")
            401, 403 -> throw FetchFailure(FetchFailure.Kind.AUTH_REQUIRED, "github requires auth")
            404 -> throw EndpointRejected("repository or endpoint not found")
        }

        // Untrusted JSON: defensive navigation only, never direct casts.
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(response.body.decodeToString())
        }.getOrElse {
            throw FetchFailure(
                com.mezo.trace.application.port.FetchFailure.Kind.NETWORK,
                "github response is not valid json",
                it,
            )
        }
        // The releases endpoint MUST return a JSON array; anything else is a
        // parser failure, never a silent empty page (Rule 3, Rule 5).
        if (root !is kotlinx.serialization.json.JsonArray) {
            throw EndpointRejected("github payload is not a release array")
        }

        val items = root.asSequence()
            .filterIsInstance<kotlinx.serialization.json.JsonObject>()
            .take(maxItemsPerCycle)
            .mapNotNull { obj -> normalize(obj, endpoint) }
            .toList()

        val nextPage = if (root.size == maxItemsPerCycle && page < MAX_PAGE) page + 1 else null
        return FetchResult(items = items, nextCursor = nextPage?.toString(), etag = response.etag)
    }

    private fun normalize(
        release: kotlinx.serialization.json.JsonObject,
        endpoint: GithubEndpoint,
    ): NormalizedItem? {
        val externalId = (release["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val tag = release.string("tag_name")
        val name = release.string("name")
        val htmlUrl = release.string("html_url")
            ?: "https://github.com/${endpoint.owner}/${endpoint.repo}/releases"
        return NormalizedItem(
            externalId = externalId,
            canonicalUrl = htmlUrl,
            title = (name?.takeIf { it.isNotBlank() } ?: tag) ?: "Untitled release",
            author = null,
            publishedAt = release.string("published_at")?.let { AdapterSupport.parseTimestamp(it) },
            contentPreview = AdapterSupport.truncatePreview(release.string("body")),
            metadata = buildMap {
                put("provider", Provider.GITHUB.name)
                put("owner", endpoint.owner)
                put("repo", endpoint.repo)
                put("kind", endpoint.kind)
                tag?.let { put("tag", it) }
                release.bool("prerelease")?.let { put("prerelease", it.toString()) }
                release.bool("draft")?.let { put("draft", it.toString()) }
            },
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.content

    private fun kotlinx.serialization.json.JsonObject.bool(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private companion object {
        const val MAX_PAGE = 10
    }
}
