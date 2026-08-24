package com.mezo.trace.ai

import com.mezo.trace.application.port.AiProvider
import com.mezo.trace.application.port.InvestigationQuery
import com.mezo.trace.application.port.WatchIntentProposal

/**
 * Deterministic, $0, offline NL interpretation (ADR-007 LocalProvider).
 *
 * Rules — honest and inspectable, no LLM:
 *  1. Source: a URL (delegated shapes: github.com URLs / owner/repo) or an
 *     explicit `owner/repo` token resolves to the exact releases endpoint.
 *  2. Keywords: quoted terms win; otherwise salient words after cue phrases
 *     ("about", "when", "related to", "for") become include-terms.
 *  3. Significance: "only critical/major" raises the floor.
 *  Anything unresolved lands in [WatchIntentProposal.uncertainties] so the
 *  user can complete it in the confirm step.
 */
class RuleBasedLocalProvider : AiProvider {

    override val id: String = "local"
    override val modelId: String = "rule-based-v1"

    override fun parseWatchIntent(input: String): WatchIntentProposal {
        val text = input.trim()
        val uncertainties = mutableListOf<String>()
        if (text.isEmpty()) {
            throw com.mezo.trace.domain.ValidationException("input", "describe what to monitor")
        }

        // --- source resolution ---
        val source = findSource(text)
        if (source == null) {
            uncertainties += "no recognizable source; add a repository or feed URL"
        }

        // --- keywords ---
        val keywords = extractKeywords(text)
        if (keywords.isEmpty()) {
            uncertainties += "no keyword filter detected; watch will match all events on the endpoint"
        }

        // --- significance floor ---
        val lower = text.lowercase()
        val minSignificance = when {
            "only critical" in lower || "critical only" in lower -> "CRITICAL"
            "major only" in lower || "only major" in lower -> "HIGH"
            else -> null
        }

        val name = buildString {
            source?.canonical?.let { append(it) }
            if (keywords.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(keywords.joinToString(" "))
            }
        }.ifEmpty { null } ?: "New watch"

        return WatchIntentProposal(
            endpointUri = source?.endpointUri,
            adapterId = source?.adapterId,
            suggestedName = name.take(120),
            keywordsInclude = keywords,
            minSignificance = minSignificance,
            uncertainties = uncertainties,
        )
    }

    override fun semanticRelevance(description: String, eventText: String): Double {
        val a = tokens(description)
        if (a.isEmpty()) return 0.0
        val bTokens = tokens(eventText)
        val overlap = a.intersect(bTokens).size.toDouble()
        return (overlap / a.size).coerceIn(0.0, 1.0) // share of description present in event
    }

    override fun parseInvestigationQuery(input: String): InvestigationQuery {
        val text = input.trim()
        if (text.isEmpty()) {
            throw com.mezo.trace.domain.ValidationException("input", "ask a question")
        }
        val lower = text.lowercase()

        // Time cues.
        val lastDays: Int? = when {
            lower.contains("today") -> 1
            lower.contains("this week") || lower.contains("last week") -> 7
            lower.contains("this month") || lower.contains("last month") -> 30
            lower.contains("this year") -> 365
            else -> Regex("""(?:last|past)\s+(\d{1,3})\s+days?""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        }

        // Entity hint: "in X" / "for X" / "about X".
        val hintRegex = Regex("""\b(?:in|for|about|regarding)\s+([A-Za-z0-9_.-]{2,64}(?:/[A-Za-z0-9_.-]{2,64})?)""", RegexOption.IGNORE_CASE)
        val entityHint = hintRegex.findAll(text)
            .map { it.groupValues[1].trim(' ', '.', '?') }
            .firstOrNull { it.lowercase() !in setOf("this month", "this week", "today") }

        // Remaining text terms as extra keyword context. Everything that is
        // question boilerplate, a time cue, or the subject itself is stripped:
        // when the hint resolves, the subject scopes the search via identity
        // rather than free text (plan §4.12).
        val excluded = buildSet {
            addAll(STOP_WORDS)
            addAll(INVESTIGATION_STOP)
            entityHint?.let { hint ->
                val h = hint.lowercase()
                add(h)
                addAll(h.split('/', '-', '.', '_'))
            }
        }
        val hintNorm = entityHint?.lowercase().orEmpty()
        val textQuery = lower
            .replace(Regex("[^a-z0-9_./ ]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim('-', '.', '/') }
            .filter { it.length > 1 }
            .filter { tok ->
                tok !in excluded &&
                    // Drop fragments of the subject itself (e.g. "acme/inv"
                    // when hint is "acme/inv-abc") — identity already scopes.
                    (hintNorm.isEmpty() || !(hintNorm.contains(tok) || tok.contains(hintNorm))) &&
                    // Drop question boilerplate variants ("what", "changed").
                    tok !in QUESTION_STOP
            }
            .distinct()
            .take(6)
            .joinToString(" ")

        return InvestigationQuery(
            textQuery = textQuery,
            entityHint = entityHint?.lowercase(),
            lastDays = lastDays?.coerceIn(1, 3650),
        )
    }

    // ---- internals ----

    private data class SourceRef(val canonical: String, val endpointUri: String, val adapterId: String)

    private fun findSource(text: String): SourceRef? {
        val url = Regex("""https?://\S+""").find(text)?.value
        if (url != null) {
            val uri = runCatching { java.net.URI(url.trimEnd('.', ',', ')')) }.getOrNull()
            val host = uri?.host?.lowercase()
            val segments = (uri?.path ?: "").trim('/').split('/').filter { it.isNotBlank() }
            if (host == "github.com" && segments.size >= 2) {
                val repo = segments[1].removeSuffix(".git")
                return SourceRef(
                    "${segments[0]}/$repo",
                    "github://${segments[0]}/$repo/releases",
                    "github",
                )
            }
            if (host != null && host !in IGNORED_HOSTS) {
                return SourceRef(host + (uri.path ?: ""), url, guessFeedAdapter(url))
            }
        }
        // Bare owner/repo token.
        Regex("""\b([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\b""").findAll(text).forEach { m ->
            val candidate = m.groupValues[0]
            if (candidate.contains('.') && !candidate.matches(REPO_SLASH)) return@forEach
            return SourceRef(candidate, "github://$candidate/releases", "github")
        }
        return null
    }

    private fun guessFeedAdapter(url: String): String =
        when {
            url.contains("atom", ignoreCase = true) -> "atom"
            else -> "rss"
        }

    private fun extractKeywords(text: String): List<String> {
        val quoted = Regex("\"([^\"]{2,64})\"").findAll(text).map { it.groupValues[1].trim() }
        val cued = CUE_PHRASES.flatMap { phrase ->
            Regex("""$phrase\s+([^.,;!?\n]{2,80})""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1] }
                .flatMap { salientTerms(it) }
        }
        return (quoted.toList() + cued)
            .map { it.lowercase() }
            .distinct()
            .filter { it !in STOP_WORDS && it.length > 1 }
            .take(MAX_KEYWORDS)
    }

    private fun salientTerms(fragment: String): List<String> =
        fragment.split(Regex("[\\s/]+"))
            .map { it.trim(' ', '.', ',', '!', '?', '\'', '"') }
            .filter { it.length > 2 && it.lowercase() !in STOP_WORDS }
            .take(5)

    private fun tokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toSet()

    private companion object {
        val IGNORED_HOSTS = setOf("www.github.com")
        val REPO_SLASH = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        const val MAX_KEYWORDS = 6

        val STOP_WORDS = setOf(
            "the", "and", "for", "when", "about", "with", "that", "this",
            "monitor", "notify", "watch", "changes", "change", "releases",
            "release", "new", "all", "everything", "from", "please", "want",
            "keep", "eye", "tell", "only", "into", "onto",
        )

        val CUE_PHRASES = listOf("about", "when", "related to", "regarding", "mentioning")

        private val QUESTION_STOP = setOf("what", "changed", "whats", "tell", "about")
        val INVESTIGATION_STOP = setOf(
            "in", "for", "about", "regarding", "of", "the", "this", "that",
            "last", "past", "days", "day", "week", "weeks", "month", "months",
            "year", "years", "today", "items", "item", "show",
        )
    }
}
