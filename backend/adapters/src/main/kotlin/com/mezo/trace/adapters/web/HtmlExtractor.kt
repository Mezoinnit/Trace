package com.mezo.trace.adapters.web

import com.mezo.trace.adapters.AdapterSupport
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.domain.Provider

/**
 * Controlled HTML extraction for the degraded web adapter (plan §8, Rule 8).
 *
 * Never renders HTML, never executes JavaScript — pure markup scanning over a
 * bounded document (≤1 MiB input is enforced upstream by the fetcher).
 *
 * Preference order: JSON-LD → `<article>` → Open Graph / basic meta → none.
 */
object HtmlExtractor {

    /** Everything produced from one page, alongside extraction notes. */
    data class Result(
        val canonicalUrl: String?,
        val title: String?,
        val items: List<NormalizedItem>,
        val notes: List<String>,
    )

    fun extract(html: ByteArray, sourceUrl: String, charset: java.nio.charset.Charset = Charsets.UTF_8): Result {
        val text = decode(html, charset)
            ?: return Result(null, null, emptyList(), listOf("unreadable document charset"))

        val canonical = canonicalOf(text) ?: sourceUrl
        val title = titleOf(text)

        val items = mutableListOf<NormalizedItem>()
        val notes = mutableListOf<String>()

        // 1. JSON-LD blocks — the structured source of truth in HTML.
        jsonLdItems(text, canonical).let { jsonItems ->
            items += jsonItems
            if (jsonItems.isNotEmpty()) notes += "json_ld_articles:${jsonItems.size}"
        }

        // 2. <article> tags — only when JSON-LD didn't produce anything, so
        //    we never double-count the same real-world item.
        if (items.isEmpty()) {
            articleItems(text, canonical).let { articleItemsList ->
                items += articleItemsList
                if (articleItemsList.isNotEmpty()) notes += "html_articles:${articleItemsList.size}"
            }
        }

        // 3. Open Graph / basic meta — last structured fallback before empty.
        if (items.isEmpty()) {
            openGraphItem(text, canonical, title)?.let { og ->
                items += og
                notes += "open_graph"
            }
        }

        if (items.isEmpty()) notes += "no_extractable_article_content"
        return Result(canonical, title, items, notes)
    }

    private fun decode(bytes: ByteArray, charset: java.nio.charset.Charset): String? =
        try { String(bytes, charset) } catch (_: Exception) { null }

    // ---- canonical & page metadata ----

    private fun canonicalOf(html: String): String? =
        Regex(
            """<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun titleOf(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()?.take(200)?.ifEmpty { null }

    // ---- JSON-LD ----

    private fun jsonLdItems(html: String, fallbackCanonical: String): List<NormalizedItem> {
        val blocks = LD_JSON_SCRIPT.findAll(html).toList()
        if (blocks.isEmpty()) return emptyList()

        return blocks.flatMap { match ->
            val rawJson = match.groupValues[1].trim()
            parseLdJsonBlock(rawJson, fallbackCanonical)
        }
    }

    private fun parseLdJsonBlock(raw: String, fallbackCanonical: String): List<NormalizedItem> {
        return try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            val list = when (parsed) {
                is kotlinx.serialization.json.JsonArray -> parsed.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                is kotlinx.serialization.json.JsonObject -> {
                    // @graph is common in WordPress / Yoast payloads.
                    val graph = parsed["@graph"] as? kotlinx.serialization.json.JsonArray
                    if (graph != null) {
                        graph.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                    } else {
                        listOf(parsed)
                    }
                }
                else -> emptyList()
            }
            list.filter { isArticleType(it) }.map { toItem(it, fallbackCanonical) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isArticleType(obj: kotlinx.serialization.json.JsonObject): Boolean {
        val type = obj["@type"] ?: return false
        val values = when (type) {
            is kotlinx.serialization.json.JsonArray ->
                type.filterIsInstance<kotlinx.serialization.json.JsonPrimitive>().map { it.content }
            is kotlinx.serialization.json.JsonPrimitive ->
                if (type.isString) listOf(type.content) else emptyList()
            else -> emptyList()
        }
        return values.any { it in ARTICLE_TYPES }
    }

    private val ARTICLE_TYPES = setOf("NewsArticle", "Article", "BlogPosting", "TechArticle")

    private fun toItem(obj: kotlinx.serialization.json.JsonObject, fallbackCanonical: String): NormalizedItem {
        fun field(name: String): String? =
            (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        val url = field("url") ?: fallbackCanonical
        return NormalizedItem(
            externalId = url ?: titleSlug(field("headline") ?: "untitled"),
            canonicalUrl = url,
            title = field("headline") ?: field("name"),
            author = field("author") ?: authorName(obj),
            publishedAt = AdapterSupport.parseTimestamp(
                field("datePublished") ?: field("dateCreated")
            ),
            contentPreview = AdapterSupport.truncatePreview(field("description")),
            metadata = mapOf(
                "format" to "json-ld",
                "provider" to Provider.WEB_PAGE.name,
            ),
        )
    }

    private fun authorName(obj: kotlinx.serialization.json.JsonObject): String? {
        return (obj["author"] as? kotlinx.serialization.json.JsonObject)
            ?.get("name")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
    }

    private fun titleSlug(value: String): String = java.util.UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    // ---- <article> tags ----

    private fun articleItems(html: String, fallbackCanonical: String): List<NormalizedItem> {
        return ARTICLE_TAG.findAll(html).take(MAX_ARTICLES).mapNotNull { match ->
            val block = match.groupValues[1]
            val heading = headingText(block) ?: return@mapNotNull null
            val link = ARTICLE_LINK.find(block)?.groupValues?.get(1)?.let { href ->
                runCatching { java.net.URI.create(fallbackCanonical).resolve(href.trim()).toString() }
                    .getOrNull()
            }
            NormalizedItem(
                externalId = link ?: titleSlug(heading),
                canonicalUrl = link,
                title = heading,
                author = null,
                publishedAt = null,
                contentPreview = AdapterSupport.truncatePreview(block),
                metadata = mapOf(
                    "format" to "html-article",
                    "provider" to Provider.WEB_PAGE.name,
                ),
            )
        }.toList()
    }

    private fun headingText(block: String): String? =
        Regex(
            """<h[12][^>]*>(.*?)</h[12]>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(block)
            ?.groupValues?.get(1)
            ?.replace(Regex("<[^>]*>"), " ")
            ?.trim()
            ?.take(200)
            ?.ifEmpty { null }

    // ---- Open Graph / basic meta ----

    private fun openGraphItem(html: String, fallbackCanonical: String, pageTitle: String?): NormalizedItem? {
        val ogTitle = metaContent(html, property = "og:title")
            ?: metaContent(html, name = "twitter:title")
            ?: pageTitle
            ?: return null
        val ogUrl = metaContent(html, property = "og:url")
            ?: fallbackCanonical
        val ogDescription = metaContent(html, property = "og:description")
            ?: metaContent(html, name = "description")
            ?: metaContent(html, name = "twitter:description")

        return NormalizedItem(
            externalId = ogUrl,
            canonicalUrl = ogUrl,
            title = ogTitle.take(200),
            author = null,
            publishedAt = null,
            contentPreview = AdapterSupport.truncatePreview(ogDescription),
            metadata = mapOf(
                "format" to "open-graph",
                "provider" to Provider.WEB_PAGE.name,
            ),
        )
    }

    private fun metaContent(html: String, property: String? = null, name: String? = null): String? {
        val attr = when {
            property != null -> """property=["']${Regex.escape(property)}["']"""
            name != null -> """name=["']${Regex.escape(name)}["']"""
            else -> return null
        }
        // attribute order varies: property/name before or after content=
        val patterns = listOf(
            Regex(
                """<meta[^>]+$attr[^>]+content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """<meta[^>]+content=["']([^"']+)["'][^>]+$attr""",
                RegexOption.IGNORE_CASE,
            ),
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1)?.trim()?.takeIf { v -> v.isNotBlank() } }
    }

    /**
     * The bounded cap on extractable articles per page (Rule 6).
     */
    const val MAX_ARTICLES = 50

    private val LD_JSON_SCRIPT = Regex(
        """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ARTICLE_TAG = Regex(
        """<article[^>]*>(.*?)</article>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ARTICLE_LINK = Regex(
        """<a[^>]+href=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
}
