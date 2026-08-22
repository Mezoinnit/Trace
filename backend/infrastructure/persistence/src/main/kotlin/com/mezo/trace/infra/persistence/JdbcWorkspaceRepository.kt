package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.application.port.WorkspaceWithRole
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.Workspace
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.UserId
import java.util.UUID

class JdbcWorkspaceRepository(private val sql: SqlConnections) : WorkspaceRepository {

    override fun insert(workspace: Workspace) {
        sql.current().prepareStatement(
            """
            INSERT INTO workspaces (id, name, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(workspace.id.value))
            ps.setString(2, workspace.name)
            ps.setObject(3, UUID.fromString(workspace.createdBy.value))
            ps.setInstant(4, workspace.createdAt)
            ps.setInstant(5, workspace.updatedAt)
            ps.executeUpdate()
        }
    }

    override fun findById(id: WorkspaceId): Workspace? {
        val uuid = runCatching { UUID.fromString(id.value) }.getOrNull() ?: return null
        sql.current().prepareStatement("SELECT * FROM workspaces WHERE id = ?").use { ps ->
            ps.setObject(1, uuid)
            ps.executeQuery().use { rs -> if (rs.next()) return rs.toWorkspace() }
        }
        return null
    }

    override fun listForUser(userId: UserId): List<WorkspaceWithRole> {
        sql.current().prepareStatement(
            """
            SELECT w.id, w.name, w.created_by, w.created_at, w.updated_at, m.role
            FROM workspaces w
            JOIN workspace_members m ON m.workspace_id = w.id
            WHERE m.user_id = ?
            ORDER BY w.created_at
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(userId.value))
            ps.executeQuery().use { rs ->
                val result = mutableListOf<WorkspaceWithRole>()
                while (rs.next()) {
                    result += WorkspaceWithRole(rs.toWorkspace(), Role.valueOf(rs.getString("role")))
                }
                return result
            }
        }
    }

    override fun rename(id: WorkspaceId, name: String, now: java.time.Instant) {
        sql.current().prepareStatement(
            "UPDATE workspaces SET name = ?, updated_at = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, name)
            ps.setInstant(2, now)
            ps.setObject(3, UUID.fromString(id.value))
            ps.executeUpdate()
        }
    }

    override fun delete(id: WorkspaceId) {
        sql.current().prepareStatement("DELETE FROM workspaces WHERE id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id.value))
            ps.executeUpdate()
        }
    }

    override fun findSoleMemberWorkspaceIds(userId: UserId): List<WorkspaceId> {
        sql.current().prepareStatement(
            """
            SELECT m.workspace_id
            FROM workspace_members m
            WHERE m.user_id = ?
            GROUP BY m.workspace_id
            HAVING count(*) = 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(userId.value))
            ps.executeQuery().use { rs ->
                val ids = mutableListOf<WorkspaceId>()
                while (rs.next()) {
                    ids += WorkspaceId(rs.getObject("workspace_id", UUID::class.java).toString())
                }
                return ids
            }
        }
    }

    override fun hasSharedMembership(userId: UserId): Boolean {
        sql.current().prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM workspace_members m
                WHERE m.user_id = ?
                  AND (SELECT count(*) FROM workspace_members s
                       WHERE s.workspace_id = m.workspace_id) > 1
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(userId.value))
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getBoolean(1)
            }
        }
    }

    private fun java.sql.ResultSet.toWorkspace(): Workspace = Workspace(
        id = WorkspaceId(getObject("id", UUID::class.java).toString()),
        name = getString("name"),
        createdBy = UserId(getObject("created_by", UUID::class.java).toString()),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )
}
