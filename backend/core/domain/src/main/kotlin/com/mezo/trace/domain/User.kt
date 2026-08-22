package com.mezo.trace.domain

/**
 * A registered user. Authentication state (password hash) lives here;
 * intelligence ownership always goes through workspaces.
 */
data class User(
    val id: UserId,
    val email: Email,
    val displayName: String?,
    val passwordHash: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)
