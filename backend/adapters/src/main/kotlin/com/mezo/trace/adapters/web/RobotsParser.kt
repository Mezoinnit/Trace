package com.mezo.trace.adapters.web

/**
 * Minimal standards-aligned robots.txt evaluation for our crawler identity
 * (plan §8 step 3). Anything we cannot parse is treated as allowed; this is a
 * best-effort politeness filter, never the security control.
 */
object RobotsParser {

    const val USER_AGENT = "TraceIntelligenceBot"

    private data class Rule(val disallow: Boolean, val path: String)

    private data class Group(val agents: List<String>, val rules: List<Rule>)

    fun isAllowed(robotsText: String?, path: String): Boolean {
        if (robotsText.isNullOrBlank()) return true

        val groups = parseGroups(robotsText)
        val own = groups.firstOrNull { g -> g.agents.any { it.equals(USER_AGENT, ignoreCase = true) } }
        val stars = groups.firstOrNull { g -> g.agents.any { it == "*" } }
        val effective = (own ?: stars)?.rules ?: return true

        val best = effective
            .filter { it.path.isNotEmpty() && path.startsWith(it.path) }
            .maxByOrNull { it.path.length + if (it.disallow) 0 else 1 }
            ?: return true

        return !best.disallow
    }

    private fun parseGroups(text: String): List<Group> {
        val groups = mutableListOf<Group>()
        var agents = mutableListOf<String>()
        val rules = mutableListOf<Rule>()

        text.lines().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim().lowercase()
            when {
                line.startsWith("user-agent:") -> {
                    if (rules.isNotEmpty()) {
                        groups += Group(agents, rules.toList())
                        agents = mutableListOf()
                        rules.clear()
                    }
                    agents += line.substringAfter(':').trim()
                }

                line.startsWith("disallow:") ->
                    rules += Rule(true, line.substringAfter(':').trim())

                line.startsWith("allow:") ->
                    rules += Rule(false, line.substringAfter(':').trim())
            }
        }

        if (agents.isNotEmpty() || rules.isNotEmpty()) groups += Group(agents, rules)
        return groups
    }
}
