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

    /** Trigram name/alias search for entities (plan §29). */
    fun search(query: String, limit: Int): List<com.mezo.trace.domain.Entity>

    /**
     * False-merge recovery primitive (plan §4.11, ADR-004): moves identifiers,
     * aliases, and event links from [loserId] to [survivorId] and marks the
     * loser as merged. Never deletes the loser row.
     */
    fun mergeInto(loserId: String, survivorId: String)

    /** Snapshot for audit/reversal bookkeeping. */
    fun identifiersOf(entityId: String): List<com.mezo.trace.domain.EntityIdentifier>

    fun aliasesOf(entityId: String): List<String>
}
