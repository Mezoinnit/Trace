package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.WorkspaceMember
import com.mezo.trace.domain.UserId
import java.util.UUID

class JdbcWorkspaceMembershipRepository(private val sql: SqlConnections) :
    WorkspaceMembershipRepository {

    override fun insert(member: WorkspaceMember) {
        sql.current().prepareStatement(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role, created_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(member.workspaceId.value))
            ps.setObject(2, UUID.fromString(member.userId.value))
            ps.setString(3, member.role.name)
            ps.setInstant(4, member.createdAt)
            ps.executeUpdate()
        }
    }

    override fun findRole(workspaceId: WorkspaceId, userId: UserId): Role? {
        sql.current().prepareStatement(
            "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(workspaceId.value))
            ps.setObject(2, runCatching { UUID.fromString(userId.value) }.getOrNull())
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return Role.valueOf(rs.getString("role"))
            }
        }
    }

    override fun listMembers(workspaceId: WorkspaceId): List<WorkspaceMember> =
        listMemberInfo(workspaceId).map { it.member }

    override fun listMemberInfo(workspaceId: WorkspaceId): List<com.mezo.trace.application.model.MemberInfo> {
        sql.current().prepareStatement(
            """
            SELECT m.workspace_id, m.user_id, m.role, m.created_at,
                   u.email AS member_email, u.display_name AS member_name
            FROM workspace_members m
            JOIN users u ON u.id = m.user_id
            WHERE m.workspace_id = ?
            ORDER BY m.created_at
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(workspaceId.value))
            ps.executeQuery().use { rs ->
                val members = mutableListOf<com.mezo.trace.application.model.MemberInfo>()
                while (rs.next()) {
                    val member =
                        WorkspaceMember(
                            workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java).toString()),
                            userId = UserId(rs.getObject("user_id", UUID::class.java).toString()),
                            role = Role.valueOf(rs.getString("role")),
                            createdAt = rs.instant("created_at"),
                        )
                    members +=
                        com.mezo.trace.application.model.MemberInfo(
                            member = member,
                            email = rs.getString("member_email"),
                            displayName = rs.getObject("member_name") as String?,
                        )
                }
                return members
            }
        }
    }
}
