package com.mezo.trace.application.usecase.evidence

import com.mezo.trace.application.port.EvidenceRepository
import com.mezo.trace.application.port.NormalizedItem
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.Evidence
import com.mezo.trace.domain.EvidenceVersion
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Persists normalized items as immutable evidence (Phase 3, ADR-006).
 *
 * Guarantees:
 * - **Idempotent**: the key `(endpoint_id, external_id)` never duplicates —
 *   repeated fetches of the same item are a no-op (Rule 4, exit criterion).
 * - **Versioned**: when an item's content changes, the prior content is
 *   preserved as an [EvidenceVersion]; history is never overwritten (Rule 5).
 * - **Traceable**: every record carries source, endpoint, external id,
 *   canonical URL and timestamps — provenance is fully reconstructable.
 */
class IngestEvidence(
    private val evidenceRepository: EvidenceRepository,
    private val tx: TxRunner,
) {

    data class IngestSummary(
        val created: Int,
        val unchanged: Int,
        val versioned: Int,
    )

    fun execute(
        sourceId: String,
        endpointId: String,
        items: List<NormalizedItem>,
    ): IngestSummary {
        if (items.isEmpty()) return IngestSummary(0, 0, 0)
        return tx.run {
            var created = 0
            var unchanged = 0
            var versioned = 0

            for (item in items) {
                val contentHash = sha256(item.contentPreview.orEmpty())
                val existing = evidenceRepository.findByExternalIdentity(endpointId, item.externalId)

                when {
                    existing == null -> {
                        evidenceRepository.insert(
                            Evidence(
                                id = UUID.randomUUID().toString(),
                                sourceId = sourceId,
                                endpointId = endpointId,
                                externalId = item.externalId,
                                canonicalUrl = item.canonicalUrl,
                                title = item.title,
                                author = item.author,
                                publishedAt = item.publishedAt,
                                discoveredAt = Instant.now(),
                                content = item.contentPreview,
                                contentHash = contentHash,
                                rawPayloadReference = null,
                                metadata = item.metadata,
                                language = item.language,
                                createdAt = Instant.now(),
                            ),
                        )
                        created++
                    }

                    existing.contentHash == contentHash ||
                        evidenceRepository.hasVersionWithHash(existing.id, contentHash) -> {
                        // Same item re-fetched; nothing to do. Already-seen
                        // historical hashes are also not re-versioned.
                        unchanged++
                    }

                    else -> {
                        val nextVersion = evidenceRepository.latestVersion(existing.id) + 1
                        evidenceRepository.appendVersion(
                            EvidenceVersion(
                                id = UUID.randomUUID().toString(),
                                evidenceId = existing.id,
                                version = nextVersion,
                                content = item.contentPreview,
                                contentHash = contentHash,
                                changedBy = "source_poll",
                                createdAt = Instant.now(),
                            ),
                        )
                        versioned++
                    }
                }
            }
            IngestSummary(created, unchanged, versioned)
        }
    }

    internal fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
