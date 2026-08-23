package com.mezo.trace.adapters

import com.mezo.trace.application.port.FetchFailure
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Shared adapter helpers: endpoint URI parsing, provider registry, and
 * normalization utilities (SOURCE_ADAPTERS.md).
 */
object AdapterSupport {

    /**
     * Normalizes a user-supplied URL for comparison and display.
     * Delegates to the domain implementation (Urls.normalize).
     */
    fun normalizeUrl(raw: String): String =
        com.mezo.trace.domain.Urls.normalize(raw).toString()

    fun truncatePreview(content: String?, maxChars: Int = 500): String? =
        content
            ?.replace(Regex("<[^>]*>"), " ") // strip tags defensively
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > maxChars) it.take(maxChars) + "…" else it }

    private val rfc822Formats = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )

    /** Parses RFC-822 or ISO-8601 timestamps; null when unparseable. */
    fun parseTimestamp(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        rfc822Formats.forEach { fmt ->
            runCatching { return Instant.from(fmt.parse(raw.trim())) }
        }
        return null
    }

    fun capabilities(vararg values: Capability): Set<Capability> = values.toSet()

    fun providerOf(id: String): Provider = Provider.valueOf(id.uppercase().replace('-', '_'))
}
