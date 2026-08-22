package com.mezo.trace.application.model

import com.mezo.trace.domain.WorkspaceMember

/**
 * Workspace member together with public profile fields for listings.
 */
data class MemberInfo(
    val member: WorkspaceMember,
    val email: String,
    val displayName: String?,
)
