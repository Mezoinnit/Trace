package com.mezo.trace.adapters.testing

import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchedResponse
import com.mezo.trace.application.port.HttpFetcher

/**
 * Deterministic stub transport for adapter/resolver tests: serves fixture
 * bytes by URL. No network involved.
 */
class StubHttpFetcher(
    private val responses: MutableMap<String, Response> = mutableMapOf(),
) : HttpFetcher {

    class Response(
        val statusCode: Int = 200,
        val contentType: String? = "application/json",
        val body: ByteArray,
        val etag: String? = null,
    )

    val requestedUrls = mutableListOf<String>()
    val requestedEtags = mutableListOf<String?>()

    private val dynamicProviders = mutableListOf<Pair<Regex, () -> Response>>()

    fun serve(url: String, response: Response) {
        responses[url] = response
    }

    /** Serves a response computed at request time (mutable fixtures). */
    fun serveDynamic(pattern: String, provider: () -> Response) {
        dynamicProviders += Regex(pattern) to provider
    }

    override fun fetch(request: FetchRequest): FetchedResponse {
        requestedUrls += request.url
        requestedEtags += request.etag

        dynamicProviders.firstOrNull { (regex, _) -> regex.matches(request.url) }?.let { (_, provider) ->
            return toFetched(request.url, provider())
        }

        val match = responses.entries.firstOrNull { (pattern, _) ->
            pattern == request.url || Regex(pattern).matches(request.url)
        } ?: throw com.mezo.trace.application.port.FetchFailure(
            com.mezo.trace.application.port.FetchFailure.Kind.NETWORK,
            "no stub for ${request.url}",
        )
        return toFetched(request.url, match.value)
    }

    private fun toFetched(url: String, response: Response): FetchedResponse =
        FetchedResponse(
            finalUrl = url,
            statusCode = response.statusCode,
            contentType = response.contentType,
            body = response.body,
            etag = response.etag,
            lastModified = null,
        )
}
