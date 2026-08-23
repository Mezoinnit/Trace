package com.mezo.trace.application.usecase.sources

import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AdapterSelector
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceHealthRepository
import com.mezo.trace.application.port.SourceRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.Capability
import com.mezo.trace.domain.Provider
import com.mezo.trace.domain.Source
import com.mezo.trace.domain.SourceEndpoint
import com.mezo.trace.domain.SourceStatus
import com.mezo.trace.domain.TrustClassification
import com.mezo.trace.domain.UserId
import com.mezo.trace.domain.ValidationException
import java.util.UUID

/**
 * Registers a confirmed source from an exact endpoint URI (plan §2.3).
 *
 * The API accepts only `{endpoint_uri, display_name?}` — every other attribute
 * is derived server-side from the selected adapter because client-supplied
 * values are untrusted (Rule 3). Sources/endpoints are global shared
 * intelligence (ADR-001): identical canonical identities converge on the same
 * rows so one endpoint is ingested once for all watchers.
 */
class CreateSource(
    private val sources: SourceRepository,
    private val endpoints: SourceEndpointRepository,
    private val health: SourceHealthRepository,
    private val audit: AuditLogRepository,
    private val adapterSelector: AdapterSelector,
    private val tx: TxRunner,
    private val clock: ClockPort,
) {

    data class Registered(
        val source: Source,
        val endpoint: SourceEndpoint,
        val sourceCreated: Boolean,
        val endpointCreated: Boolean,
    )

    fun execute(actor: UserId, endpointUri: String, adapterId: String, displayNameHint: String?): Registered {
        return tx.run {
            // Server-side validation: the endpoint must be backed by the
            // adapter named in the confirmed proposal, and that adapter must
            // explicitly support the URI. Client values are untrusted.
            val adapter = adapterSelector.byId(adapterId)
                ?: throw ValidationException("adapter_id", "unknown adapter")
            if (!adapter.supports(endpointUri)) {
                throw ValidationException("endpoint_uri", "adapter cannot handle this endpoint")
            }

            val derived = deriveMetadata(adapter.id, adapter.provider, endpointUri, displayNameHint)

            val now = clock.now()

            var source = sources.findByCanonicalIdentity(
                derived.provider.name,
                derived.sourceType,
                derived.canonicalIdentity,
            )
            var sourceCreated = false
            if (source == null) {
                source = Source(
                    id = UUID.randomUUID().toString(),
                    provider = derived.provider,
                    sourceType = derived.sourceType,
                    canonicalIdentity = derived.canonicalIdentity,
                    displayName = derived.displayName,
                    trust = derived.trust,
                    verificationState = "UNVERIFIED",
                    capabilities = adapter.capabilitiesFor(endpointUri),
                    status = SourceStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                )
                sources.insert(source)
                sourceCreated = true
            }
            val resolvedSource = source!!

            var endpoint = endpoints.findByUri(endpointUri)
            var endpointCreated = false
            if (endpoint == null) {
                endpoint = SourceEndpoint(
                    id = UUID.randomUUID().toString(),
                    sourceId = resolvedSource.id,
                    endpointUri = endpointUri,
                    displayUrl = if (endpointUri.startsWith("http")) endpointUri else null,
                    endpointType = derived.endpointType,
                    capabilities = adapter.capabilitiesFor(endpointUri),
                    adapter = adapter.id,
                    status = SourceStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                )
                endpoints.insert(endpoint)
                health.initialize(endpoint.id)
                endpointCreated = true
            }
            val resolvedEndpoint = endpoint!!

            if (sourceCreated) {
                audit.append(
                    AuditEntry(
                        action = "SOURCE_ADDED",
                        actorType = ActorType.USER,
                        actorId = actor.value,
                        resourceType = "source",
                        resourceId = resolvedSource.id,
                        metadata = mapOf("name" to resolvedSource.displayName.orEmpty()),
                    ),
                )
            }
            if (endpointCreated) {
                audit.append(
                    AuditEntry(
                        action = "SOURCE_ENDPOINT_ADDED",
                        actorType = ActorType.USER,
                        actorId = actor.value,
                        resourceType = "source_endpoint",
                        resourceId = resolvedEndpoint.id,
                        metadata = mapOf("endpoint_uri" to resolvedEndpoint.endpointUri),
                    ),
                )
            }

            Registered(resolvedSource, resolvedEndpoint, sourceCreated, endpointCreated)
        }
    }

    /** Metadata derivation from adapter identity — never from client input. */
    internal fun deriveMetadata(
        adapterId: String,
        provider: Provider,
        endpointUri: String,
        displayNameHint: String?,
    ): DerivedMetadata = when {
        endpointUri.startsWith(GITHUB_SCHEME) -> {
            val parts = endpointUri.removePrefix(GITHUB_SCHEME).split('/')
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw ValidationException("endpoint_uri", "malformed github endpoint")
            }
            val kind = parts.getOrNull(2) ?: "releases"
            DerivedMetadata(
                provider = Provider.GITHUB,
                sourceType = SOURCE_TYPE_REPOSITORY,
                canonicalIdentity = "${parts[0]}/${parts[1]}",
                endpointType = if (kind == "tags") ENDPOINT_TAGS else ENDPOINT_RELEASES,
                trust = TrustClassification.OFFICIAL,
                displayName = displayNameHint?.takeIf { it.isNotBlank() } ?: "${parts[0]}/${parts[1]}",
            )
        }

        else -> DerivedMetadata(
            provider = provider,
            sourceType = if (adapterId == ADAPTER_WEB_PAGE) SOURCE_TYPE_PAGE else SOURCE_TYPE_FEED,
            canonicalIdentity = endpointUri,
            endpointType = when (adapterId) {
                ADAPTER_WEB_PAGE -> ENDPOINT_PAGE
                ADAPTER_ATOM -> ENDPOINT_FEED
                else -> ENDPOINT_FEED
            },
            trust = TrustClassification.UNKNOWN,
            displayName = displayNameHint?.takeIf { it.isNotBlank() }
                ?: runCatching { java.net.URI.create(endpointUri).host }.getOrNull()
                ?: endpointUri,
        )
    }

    data class DerivedMetadata(
        val provider: Provider,
        val sourceType: String,
        val canonicalIdentity: String,
        val endpointType: String,
        val trust: TrustClassification,
        val displayName: String?,
    )

    companion object {
        private const val GITHUB_SCHEME = "github://"
        private const val ADAPTER_WEB_PAGE = "web_page"
        private const val ADAPTER_ATOM = "atom"
        const val SOURCE_TYPE_REPOSITORY = "REPOSITORY"
        const val SOURCE_TYPE_FEED = "FEED"
        const val SOURCE_TYPE_PAGE = "PAGE"
        const val ENDPOINT_RELEASES = "RELEASES"
        const val ENDPOINT_TAGS = "TAGS"
        const val ENDPOINT_FEED = "FEED"
        const val ENDPOINT_PAGE = "PAGE"
    }
}
