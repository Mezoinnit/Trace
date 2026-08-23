package com.mezo.trace.adapters.github

import com.mezo.trace.application.port.EndpointRejected
import com.mezo.trace.adapters.AdapterSupport

/**
 * GitHub endpoint identity: `github://{owner}/{repo}/{kind}`.
 *
 * The exact resource is the releases stream of one repository — never the
 * whole provider (plan §2.3). Parsing is strict: owner/repo character sets
 * are GitHub's own, and anything else is rejected.
 */
data class GithubEndpoint(
    val owner: String,
    val repo: String,
    val kind: String,
) {

    val uri: String = "github://$owner/$repo/$kind"

    companion object {

        private val SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")

        fun parse(endpointUri: String): GithubEndpoint {
            if (!endpointUri.startsWith(SCHEME)) throw EndpointRejected("not a github endpoint")
            val rest = endpointUri.removePrefix(SCHEME)
            val parts = rest.split('/')
            if (parts.size != 3 || parts.any { it.isBlank() }) {
                throw EndpointRejected("malformed github endpoint")
            }
            val (owner, repo, kind) = parts
            requireSegment(owner, "owner")
            requireSegment(repo, "repo")
            if (kind !in SUPPORTED_KINDS) {
                throw EndpointRejected("unsupported github endpoint kind '$kind'")
            }
            return GithubEndpoint(owner, repo, kind)
        }

        /**
         * Recognizes user-supplied repository URLs and maps them to the
         * exact releases endpoint. Never broadens scope.
         */
        fun fromUrl(rawUrl: String): GithubEndpoint? {
            val normalized = runCatching { AdapterSupport.normalizeUrl(rawUrl) }.getOrNull()
                ?: return null
            val host = java.net.URI.create(normalized).host ?: return null
            if (host != "github.com" && host != "www.github.com") return null

            val path = java.net.URI.create(normalized).path?.trim('/') ?: return null
            val segments = path.split('/').filter { it.isNotBlank() }
            // github.com/{owner}/{repo}[/releases|/tags]
            if (segments.size < 2) return null
            val (owner, repoRaw) = segments.take(2)
            val repo = repoRaw.removeSuffix(".git")
            if (!SEGMENT.matches(owner) || !SEGMENT.matches(repo)) return null

            val kind = when (segments.getOrNull(2)) {
                null, "releases" -> "releases"
                "tags" -> "tags"
                else -> return null
            }
            return GithubEndpoint(owner, repo, kind)
        }

        private fun requireSegment(value: String, name: String) {
            if (!SEGMENT.matches(value)) throw EndpointRejected("invalid github $name segment")
        }

        const val SCHEME = "github://"
        val SUPPORTED_KINDS = setOf("releases", "tags")
    }
}
