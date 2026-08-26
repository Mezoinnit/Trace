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

    fun findById(evidenceId: String): com.mezo.trace.domain.Evidence?

    /** Full version history, oldest first. */
    fun versionsFor(evidenceId: String): List<com.mezo.trace.domain.EvidenceVersion>

    /**
     * Moves the mutable display fields to the latest content after a version
     * was appended — the prior value is already preserved in versions
     * (ADR-006 immutability applies to history, not to the current view).
     */
    fun updateContent(evidenceId: String, content: String?, contentHash: String)

    fun appendVersion(version: EvidenceVersion)
}
