package com.mezo.trace.application.port

import com.mezo.trace.domain.Evidence
import com.mezo.trace.domain.EvidenceVersion

interface EvidenceRepository {

    fun findByExternalIdentity(endpointId: String, externalId: String): Evidence?

    /**
     * Inserts evidence; returns false when (endpoint_id, external_id) already
     * exists — the idempotency path, never an error (Rule 4).
     */
    fun insert(evidence: Evidence): Boolean

    /** Latest version number for an evidence record; 0 when none. */
    fun latestVersion(evidenceId: String): Int

    /** Finds a prior version by content hash (change detection). */
    fun hasVersionWithHash(evidenceId: String, contentHash: String): Boolean

    fun appendVersion(version: EvidenceVersion)
}
