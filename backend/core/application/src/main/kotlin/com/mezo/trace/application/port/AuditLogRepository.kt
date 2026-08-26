package com.mezo.trace.application.port

import com.mezo.trace.application.model.AuditEntry

interface AuditLogRepository {
    fun append(entry: AuditEntry)

    /** Chronological audit trail for one resource (history surfaces). */
    fun listForResource(
        resourceType: String,
        resourceId: String,
    ): List<com.mezo.trace.application.port.EventHistoryEntry>
}
