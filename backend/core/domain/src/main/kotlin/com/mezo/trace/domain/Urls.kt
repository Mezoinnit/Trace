package com.mezo.trace.domain

/**
 * URL normalization for identity and comparison (plan §7).
 *
 * Lowercases scheme+host, drops fragments and default ports, defaults the
 * path to "/". Query strings are preserved (feeds may require them).
 * Normalized URLs are still untrusted input — validation happens at fetch.
 */
object Urls {

    fun normalize(raw: String): java.net.URI {
        val trimmed = raw.trim()
        val uri = try {
            java.net.URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid url", e)
        }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("missing scheme")
        if (scheme != "https" && scheme != "http") throw IllegalArgumentException("unsupported scheme")
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("missing host")
        if (host.isEmpty()) throw IllegalArgumentException("missing host")

        val port = when {
            uri.port == -1 -> ""
            (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80) -> ""
            else -> ":${uri.port}"
        }
        var path = uri.rawPath ?: ""
        if (path.isEmpty()) path = "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return java.net.URI("$scheme://$host$port$path$query")
    }
}
