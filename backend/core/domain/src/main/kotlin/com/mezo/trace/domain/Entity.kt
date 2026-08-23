package com.mezo.trace.domain

import java.time.Instant

/**
 * A real or conceptual thing being monitored or referenced (plan §4.10).
 * Identity: structured external identifiers + aliases — never names alone.
 */
data class Entity(
    val id: String,
    val type: EntityType,
    val canonicalName: String,
    val description: String? = null,
    val createdAt: Instant = java.time.Instant.EPOCH,
    val updatedAt: Instant = java.time.Instant.EPOCH,
)

/**
 * V1 entity types. Domains add types via migration without replacing the
 * pipeline (plan §37).
 */
enum class EntityType {
    COMPANY,
    PERSON,
    PRODUCT,
    PROJECT,
    SOFTWARE,
    REPOSITORY,
    ORGANIZATION,
    CUSTOM,
}

data class EntityIdentifier(
    val entityType: String, // e.g. GITHUB_REPO, TICKER, DOI
    val value: String,
)

data class EntityRelationship(
    val id: String,
    val sourceEntityId: String,
    val targetEntityId: String,
    /** e.g. owns, develops, contains, listed_as */
    val relation: String,
    val createdAt: Instant = java.time.Instant.EPOCH,
)
