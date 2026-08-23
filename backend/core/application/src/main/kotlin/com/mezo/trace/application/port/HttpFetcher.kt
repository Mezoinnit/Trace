package com.mezo.trace.application.port

/**
 * Request for an outbound fetch of an untrusted URL.
 */
data class FetchRequest(
    val url: String,
    val etag: String? = null,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024
    }
}

/**
 * Bounded response from an outbound fetch. Bodies are never larger than the
 * request cap; redirects are revalidated by the implementation (ADR-011).
 */
class FetchedResponse(
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
    val etag: String?,
    val lastModified: String?,
)

/**
 * Fetch failure taxonomy distinguishing retryable from permanent failures
 * (plan §48). BLOCKED_* kinds are permanent and must never be retried.
 */
class FetchFailure(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Kind {
        UNSUPPORTED_SCHEME,
        BLOCKED_PRIVATE_ADDRESS,
        DNS_FAILURE,
        NETWORK,
        TIMEOUT,
        TOO_LARGE,
        RATE_LIMITED,
        AUTH_REQUIRED,
    }
}

/**
 * The only way the system talks to external URLs.
 *
 * Implementations enforce SSRF protections: protocol allowlist, private/
 * link-local/metadata range blocking by resolved IP, redirect revalidation,
 * timeouts and hard response-size caps (SECURITY.md §3, ADR-011).
 */
interface HttpFetcher {
    fun fetch(request: FetchRequest): FetchedResponse
}
