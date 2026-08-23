package com.mezo.trace.adapters

import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchedResponse
import com.mezo.trace.application.port.HttpFetcher

/**
 * Deterministic stub transport for adapter/resolver tests: serves fixture
 * bytes by URL. No network involved.
 */
class StubFetcher(
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

    fun serve(url: String, response: Response) {
        responses[url] = response
    }

    override fun fetch(request: FetchRequest): FetchedResponse {
        requestedUrls += request.url
        requestedEtags += request.etag
        val match = responses.entries.firstOrNull { (pattern, _) ->
            pattern == request.url || Regex(pattern).matches(request.url)
        } ?: throw com.mezo.trace.application.port.FetchFailure(
            com.mezo.trace.application.port.FetchFailure.Kind.NETWORK,
            "no stub for ${request.url}",
        )
        return FetchedResponse(
            finalUrl = request.url,
            statusCode = match.value.statusCode,
            contentType = match.value.contentType,
            body = match.value.body,
            etag = match.value.etag,
            lastModified = null,
        )
    }
}
