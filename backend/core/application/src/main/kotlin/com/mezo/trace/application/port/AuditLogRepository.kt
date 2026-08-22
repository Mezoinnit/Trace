package com.mezo.trace.application.port

import com.mezo.trace.application.model.AuditEntry

interface AuditLogRepository {
    fun append(entry: AuditEntry)
}
