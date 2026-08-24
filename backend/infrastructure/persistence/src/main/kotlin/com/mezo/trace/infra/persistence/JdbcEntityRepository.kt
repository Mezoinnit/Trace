package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.EntityRepository
import com.mezo.trace.domain.Entity
import com.mezo.trace.domain.EntityIdentifier
import com.mezo.trace.domain.EntityRelationship
import com.mezo.trace.domain.EntityType
import java.sql.ResultSet
import java.util.UUID

class JdbcEntityRepository(private val sql: SqlConnections) : EntityRepository {

    override fun findById(id: String): Entity? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return sql.current().prepareStatement("SELECT * FROM entities WHERE id = ?").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEntity() else null }
        }
    }

    override fun findByIdentifier(idType: String, value: String): Entity? =
        sql.current().prepareStatement(
            """
            SELECT e.* FROM entities e
            JOIN entity_identifiers i ON i.entity_id = e.id
            WHERE i.id_type = ? AND i.id_value = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, idType)
            ps.setString(2, value)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEntity() else null }
        }

    override fun findByCanonicalName(type: String, name: String): Entity? =
        sql.current().prepareStatement(
            "SELECT * FROM entities WHERE type = ? AND lower(canonical_name) = lower(?)",
        ).use { ps ->
            ps.setString(1, type)
            ps.setString(2, name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEntity() else null }
        }

    override fun findByAlias(alias: String): Entity? =
        sql.current().prepareStatement(
            "SELECT e.* FROM entities e JOIN entity_aliases a ON a.entity_id = e.id WHERE lower(a.alias) = lower(?)",
        ).use { ps ->
            ps.setString(1, alias.trim())
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEntity() else null }
        }

    override fun insert(entity: Entity): Entity {
        val id = UUID.randomUUID()
        sql.current().prepareStatement(
            """
            INSERT INTO entities (id, type, canonical_name, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, now(), now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, entity.type.name)
            ps.setString(3, entity.canonicalName)
            ps.setNullableString(4, entity.description)
            ps.executeUpdate()
        }
        // Return the authoritative row (insert may have lost a race).
        return findById(id.toString()) ?: error("entity insert vanished")
    }

    override fun addAlias(entityId: String, alias: String) {
        sql.current().prepareStatement(
            "INSERT INTO entity_aliases (entity_id, alias) VALUES (?, ?) ON CONFLICT DO NOTHING",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(entityId))
            ps.setString(2, alias.trim())
            ps.executeUpdate()
        }
    }

    override fun addIdentifier(entityId: String, identifier: EntityIdentifier) {
        sql.current().prepareStatement(
            "INSERT INTO entity_identifiers (entity_id, id_type, id_value) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(entityId))
            ps.setString(2, identifier.entityType)
            ps.setString(3, identifier.value)
            ps.executeUpdate()
        }
    }

    override fun addRelationship(relationship: EntityRelationship): Boolean =
        sql.current().prepareStatement(
            """
            INSERT INTO entity_relationships (id, source_entity_id, target_entity_id, relation)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (source_entity_id, target_entity_id, relation) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(relationship.id))
            ps.setObject(2, UUID.fromString(relationship.sourceEntityId))
            ps.setObject(3, UUID.fromString(relationship.targetEntityId))
            ps.setString(4, relationship.relation)
            ps.executeUpdate() > 0
        }

    override fun listEventEntities(eventId: String): List<Entity> {
        val uuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            """
            SELECT e.* FROM entities e
            JOIN event_entities ee ON ee.entity_id = e.id
            WHERE ee.event_id = ?
            ORDER BY e.canonical_name
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Entity>()
                while (rs.next()) out += rs.toEntity()
                out
            }
        }
    }

    override fun linkEvent(eventId: String, entityId: String, role: String) {
        sql.current().prepareStatement(
            "INSERT INTO event_entities (event_id, entity_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(eventId))
            ps.setObject(2, UUID.fromString(entityId))
            ps.setString(3, role)
            ps.executeUpdate()
        }
    }

    override fun search(query: String, limit: Int): List<com.mezo.trace.domain.Entity> =
        sql.current().prepareStatement(
            """
            SELECT DISTINCT e.*, similarity(e.canonical_name, ?) AS score
            FROM entities e
            LEFT JOIN entity_aliases a ON a.entity_id = e.id
            WHERE (e.canonical_name % ?::text OR a.alias % ?::text OR lower(a.alias) = lower(?))
              AND e.merged_into_entity_id IS NULL
            ORDER BY score DESC, e.canonical_name
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            val q = query.trim()
            ps.setString(1, q)
            ps.setString(2, q)
            ps.setString(3, q)
            ps.setString(4, q)
            ps.setInt(5, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<com.mezo.trace.domain.Entity>()
                while (rs.next()) out += rs.toEntity()
                out
            }
        }

    override fun eventsForEntity(entityId: String, limit: Int): List<com.mezo.trace.domain.Event> {
        val uuid = runCatching { UUID.fromString(entityId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            """
            SELECT ev.* FROM events ev
            JOIN event_entities ee ON ee.event_id = ev.id
            WHERE ee.entity_id = ?
            ORDER BY ev.occurred_at DESC NULLS LAST, ev.id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<com.mezo.trace.domain.Event>()
                while (rs.next()) out += EVENT_MAPPER.fromRow(rs)
                out
            }
        }
    }

    private fun ResultSet.toEntity(): Entity = Entity(
        id = getObject("id", UUID::class.java).toString(),
        type = EntityType.valueOf(getString("type")),
        canonicalName = getString("canonical_name"),
        description = getObject("description") as String?,
        mergedIntoEntityId = getObject("merged_into_entity_id")
            ?.let { getObject("merged_into_entity_id", UUID::class.java).toString() },
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    override fun identifiersOf(entityId: String): List<com.mezo.trace.domain.EntityIdentifier> {
        val uuid = runCatching { UUID.fromString(entityId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            "SELECT id_type, id_value FROM entity_identifiers WHERE entity_id = ?",
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<com.mezo.trace.domain.EntityIdentifier>()
                while (rs.next()) out += com.mezo.trace.domain.EntityIdentifier(rs.getString("id_type"), rs.getString("id_value"))
                out
            }
        }
    }

    override fun aliasesOf(entityId: String): List<String> {
        val uuid = runCatching { UUID.fromString(entityId) }.getOrNull() ?: return emptyList()
        return sql.current().prepareStatement(
            "SELECT alias FROM entity_aliases WHERE entity_id = ?",
        ).use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getString("alias")
                out
            }
        }
    }

    override fun mergeInto(loserId: String, survivorId: String) {
        val loser = runCatching { UUID.fromString(loserId) }.getOrNull() ?: return
        val survivor = UUID.fromString(survivorId)

        // Move identifiers that do not already exist on the survivor, then
        // drop the remainder (their values are preserved on the survivor).
        sql.current().prepareStatement(
            """
            UPDATE entity_identifiers i
            SET entity_id = ?
            WHERE i.entity_id = ?
              AND NOT EXISTS (
                SELECT 1 FROM entity_identifiers x
                WHERE x.id_type = i.id_type AND x.id_value = i.id_value AND x.entity_id = ?
              )
            """.trimIndent(),
        ).use { ps -> ps.setObject(1, survivor); ps.setObject(2, loser); ps.setObject(3, survivor); ps.executeUpdate() }

        // Same pattern for aliases.
        sql.current().prepareStatement(
            """
            UPDATE entity_aliases a
            SET entity_id = ?
            WHERE a.entity_id = ?
              AND NOT EXISTS (
                SELECT 1 FROM entity_aliases x
                WHERE lower(x.alias) = lower(a.alias) AND x.entity_id = ?
              )
            """.trimIndent(),
        ).use { ps -> ps.setObject(1, survivor); ps.setObject(2, loser); ps.setObject(3, survivor); ps.executeUpdate() }

        // Event links converge on the survivor.
        sql.current().prepareStatement(
            """
            UPDATE event_entities ee
            SET entity_id = ?
            WHERE ee.entity_id = ?
              AND NOT EXISTS (
                SELECT 1 FROM event_entities x
                WHERE x.event_id = ee.event_id AND x.entity_id = ?
              )
            """.trimIndent(),
        ).use { ps -> ps.setObject(1, survivor); ps.setObject(2, loser); ps.setObject(3, survivor); ps.executeUpdate() }

        sql.current().prepareStatement("DELETE FROM entity_identifiers WHERE entity_id = ?").use { ps ->
            ps.setObject(1, loser); ps.executeUpdate()
        }
        sql.current().prepareStatement("DELETE FROM entity_aliases WHERE entity_id = ?").use { ps ->
            ps.setObject(1, loser); ps.executeUpdate()
        }

        // Mark the loser — its row is never deleted (Rule 5).
        sql.current().prepareStatement(
            "UPDATE entities SET merged_into_entity_id = ?, updated_at = now() WHERE id = ?",
        ).use { ps -> ps.setObject(1, survivor); ps.setObject(2, loser); ps.executeUpdate() }
    }

    private companion object {
        val EVENT_MAPPER = EventRowMapper()
    }
}
