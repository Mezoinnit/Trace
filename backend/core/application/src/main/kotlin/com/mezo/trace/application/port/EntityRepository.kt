package com.mezo.trace.application.port

import com.mezo.trace.domain.Entity
import com.mezo.trace.domain.EntityIdentifier
import com.mezo.trace.domain.EntityRelationship

interface EntityRepository {

    fun findById(id: String): Entity?

    /** Identity lookup by structured external identifier (plan §4.12). */
    fun findByIdentifier(idType: String, value: String): Entity?

    /** Case-insensitive canonical-name lookup within a type. */
    fun findByCanonicalName(type: String, name: String): Entity?

    /** Alias → entity resolution (case-insensitive). */
    fun findByAlias(alias: String): Entity?

    fun insert(entity: Entity): Entity

    fun addAlias(entityId: String, alias: String)

    fun addIdentifier(entityId: String, identifier: EntityIdentifier)

    fun addRelationship(relationship: EntityRelationship): Boolean

    fun listEventEntities(eventId: String): List<Entity>

    fun linkEvent(eventId: String, entityId: String, role: String = "SUBJECT")

    /** Events referencing this entity, newest first (timelines, Phase 12). */
    fun eventsForEntity(entityId: String, limit: Int): List<com.mezo.trace.domain.Event>
}
