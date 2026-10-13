package com.mezo.trace.infra.fetch

import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.application.port.FetchRequest
import com.mezo.trace.application.port.FetchedResponse
import com.mezo.trace.application.port.HttpFetcher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * SSRF-hardened HTTP fetcher (ADR-011).
 *
 * - HTTPS only by default (`FETCH_ALLOW_HTTP=true` relaxes for local dev only)
 * - every hop's host is DNS-resolved and IP-validated before connecting
 * - redirects followed manually with revalidation, bounded count
 * - hard response-size cap; gzip decompression also capped
 * - connect + total timeouts
 */
class SafeHttpFetcher(
    private val allowHttp: Boolean = false,
    private val maxRedirects: Int = 3,
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val totalTimeout: Duration = Duration.ofSeconds(15),
    /**
     * Injectable DNS so rebinding defenses are testable and so the guard can
     * re-resolve immediately before every connection (TOCTOU narrowing, ADR-011).
     */
    private val dns: SsrfGuard.DnsResolver = SsrfGuard.systemDns,
) : HttpFetcher {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(connectTimeout)
        .build()

    override fun fetch(request: FetchRequest): FetchedResponse {
        val deadline = System.nanoTime() + totalTimeout.toNanos()

        var uri = parseAndValidateInitial(request.url)
        var response = send(uri, request.etag, remainingMs(deadline))

        repeat(maxRedirects) {
            val status = response.statusCode()
            if (!isRedirect(status)) return@repeat

            val location = response.headers().firstValue("Location").orElse(null)
                ?: throw FetchFailure(FetchFailure.Kind.NETWORK, "redirect without Location")
            // Revalidate the redirect target host/IPs before following (T2).
            uri = SsrfGuard.requireValidRedirect(uri, location, dns)
            requireAllowedScheme(uri)

            val etag = request.etag
            response = send(uri, etag, remainingMs(deadline))
        }

        val status = response.statusCode()
        if (isRedirect(status)) {
            throw FetchFailure(FetchFailure.Kind.NETWORK, "too many redirects")
        }
        if (status == 429) {
            throw FetchFailure(FetchFailure.Kind.RATE_LIMITED, "rate limited by remote host")
        }
        if (status == 401 || status == 403) {
            throw FetchFailure(FetchFailure.Kind.AUTH_REQUIRED, "remote host requires auth or forbids access")
        }

        val body = readBounded(response, request.maxBytes)

        return FetchedResponse(
            finalUrl = uri.toString(),
            statusCode = status,
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            body = body,
            etag = response.headers().firstValue("ETag").orElse(null),
            lastModified = response.headers().firstValue("Last-Modified").orElse(null),
        )
    }

    private fun parseAndValidateInitial(rawUrl: String): URI {
        val uri = runCatching { URI.create(rawUrl.trim()) }.getOrElse {
            throw FetchFailure(FetchFailure.Kind.UNSUPPORTED_SCHEME, "invalid url")
        }
        requireAllowedScheme(uri)
        SsrfGuard.requirePublicHost(uri.host ?: "", dns)
        return uri
    }

    private fun requireAllowedScheme(uri: URI) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "https") return
        if (scheme == "http" && allowHttp) return
        throw FetchFailure(
            FetchFailure.Kind.UNSUPPORTED_SCHEME,
            "blocked scheme '${uri.scheme}' — HTTPS required",
        )
    }

    /**
     * Re-resolves and re-validates the target host immediately before every
     * connection using the injected [dns] (ADR-011 T2, Phase 14 matrix: DNS
     * rebinding TOCTOU). A hostname that resolved public at parse time but
     * rebinds to a private address before connect is rejected here.
     */
    private fun revalidateBeforeSend(uri: URI) {
        val host = uri.host ?: return // literal-IP URIs have no host to rebind
        SsrfGuard.requirePublicHost(host, dns)
    }

    private fun send(uri: URI, etag: String?, timeoutMs: Long): HttpResponse<java.io.InputStream> {
        revalidateBeforeSend(uri)
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1)))
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", USER_AGENT)
            .GET()

        if (!etag.isNullOrBlank()) {
            builder.header("If-None-Match", etag)
        }

        return try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw FetchFailure(FetchFailure.Kind.TIMEOUT, "request timed out", e)
        } catch (e: java.net.ConnectException) {
            throw FetchFailure(FetchFailure.Kind.NETWORK, "connection failed", e)
        } catch (e: java.io.IOException) {
            throw FetchFailure(FetchFailure.Kind.NETWORK, "network error: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FetchFailure(FetchFailure.Kind.NETWORK, "interrupted", e)
        }
    }

    private fun readBounded(
        response: HttpResponse<java.io.InputStream>,
        maxBytes: Long,
    ): ByteArray {
        response.body().use { raw ->
            val isGzip = response.headers().allValues("Content-Encoding").any { it.equals("gzip", true) }
            val stream = if (isGzip) {
                try {
                    GZIPInputStream(raw)
                } catch (_: java.io.IOException) {
                    raw
                }
            } else {
                raw
            }

            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val n =
                    try {
                        stream.read(buffer)
                    } catch (e: java.io.IOException) {
                        throw FetchFailure(FetchFailure.Kind.NETWORK, "read failed", e)
                    }
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw FetchFailure(
                        FetchFailure.Kind.TOO_LARGE,
                        "response exceeds $maxBytes bytes",
                    )
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun isRedirect(status: Int): Boolean =
        status in 301..308 && status != 306

    private fun remainingMs(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceIn(1, totalTimeout.toMillis())

    private companion object {
        const val USER_AGENT = "TraceIntelligenceBot/0.1 (+https://trace.example/bot)"
    }
}
