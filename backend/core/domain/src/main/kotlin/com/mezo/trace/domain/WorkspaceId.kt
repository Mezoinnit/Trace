package com.mezo.trace.domain

/**
 * Strongly typed tenant identifier.
 *
 * Every tenant-owned record in the platform carries a workspace_id. The id is
 * always resolved from the authenticated identity server-side and never from
 * client input. See ADR-001 and ADR-010.
 */
@JvmInline
value class WorkspaceId(val value: String)