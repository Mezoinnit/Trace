package com.mezo.trace.application.usecase.entities

import com.mezo.trace.application.port.EntityRepository
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.domain.Entity
import com.mezo.trace.domain.EntityIdentifier
import com.mezo.trace.domain.EntityType

/**
 * Deterministic identity resolution (plan §4.11–4.12).
 *
 * Lookup order — structured identifiers FIRST, names never trusted alone:
 * 1. identifier (e.g. `GITHUB_REPO:owner/repo`)
 * 2. alias
 * 3. canonical name within type
 * 4. create new with identifier + aliases registered
 *
 * False merges are recoverable: nothing is ever deleted; a future merge/
 * unmerge use case can repoint identifiers (Phase 6 exit criterion).
 */
class ResolveEntity(
    private val entities: EntityRepository,
    private val tx: TxRunner,
) {

    data class Request(
        val type: EntityType,
        val canonicalName: String,
        val identifiers: List<EntityIdentifier> = emptyList(),
        val aliases: List<String> = emptyList(),
        val description: String? = null,
    )

    fun execute(request: Request): Entity {
        return tx.run {
            // 1. Structured identifiers are authoritative.
            request.identifiers.firstNotNullOfOrNull { (type, value) ->
                entities.findByIdentifier(type, value)
            }?.let { entity ->
                registerAdditionalAliases(entity, request)
                return@run entity
            }

            // 2/3. Alias or canonical-name match within the same type.
            val byAlias = request.aliases.firstNotNullOfOrNull { entities.findByAlias(it) }
                ?.takeIf { it.type == request.type }
            val byName = entities.findByCanonicalName(request.type.name, request.canonicalName)
            val existing = byAlias ?: byName

            if (existing != null) {
                // Backfill identifiers so future lookups hit path 1.
                request.identifiers.forEach { entities.addIdentifier(existing.id, it) }
                registerAdditionalAliases(existing, request)
                return@run existing
            }

            // 4. Create.
            val created = entities.insert(
                Entity(
                    id = "", // assigned by repository
                    type = request.type,
                    canonicalName = request.canonicalName.trim(),
                    description = request.description,
                ),
            )
            request.identifiers.forEach { entities.addIdentifier(created.id, it) }
            entities.addAlias(created.id, created.canonicalName)
            registerAdditionalAliases(created, request)
            created
        }
    }

    private fun registerAdditionalAliases(entity: Entity, request: Request) {
        entities.addAlias(entity.id, entity.canonicalName)
        request.aliases.forEach { alias -> entities.addAlias(entity.id, alias) }
    }
}
