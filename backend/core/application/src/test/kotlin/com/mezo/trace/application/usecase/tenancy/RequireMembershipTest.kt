package com.mezo.trace.application

import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.WorkspaceMember
import com.mezo.trace.domain.UserId
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertFailsWith

private class InMemoryMemberships(
    private val roles: MutableMap<Pair<String, String>, Role> = mutableMapOf(),
) : WorkspaceMembershipRepository {
    override fun insert(member: WorkspaceMember) {
        roles[member.workspaceId.value to member.userId.value] = member.role
    }

    override fun findRole(workspaceId: WorkspaceId, userId: UserId): Role? =
        roles[workspaceId.value to userId.value]

    override fun listMembers(workspaceId: WorkspaceId): List<WorkspaceMember> = emptyList()
    override fun listMemberInfo(workspaceId: WorkspaceId): List<com.mezo.trace.application.model.MemberInfo> = emptyList()
}

class RequireMembershipTest {

    private val tx = object : TxRunner {
        override fun <T> run(block: () -> T): T = block()
    }

    private val workspace = WorkspaceId(UUID.randomUUID().toString())
    private val user = UserId(UUID.randomUUID().toString())
    private val outsider = UserId(UUID.randomUUID().toString())

    @Test
    fun `members get their role`() {
        val repo = InMemoryMemberships()
        repo.insert(WorkspaceMember(workspace, user, Role.OWNER, Instant.now()))
        val requireMembership = RequireMembership(repo, tx)

        assertEquals(Role.OWNER, requireMembership.execute(user, workspace))
    }

    @Test
    fun `non-members get NotFound to prevent tenant enumeration`() {
        val requireMembership = RequireMembership(InMemoryMemberships(), tx)

        assertFailsWith<AppError.NotFound> { requireMembership.execute(outsider, workspace) }
    }

    @Test
    fun `requireOwner rejects non-owner members`() {
        val repo = InMemoryMemberships()
        repo.insert(WorkspaceMember(workspace, user, Role.MEMBER, Instant.now()))
        val requireMembership = RequireMembership(repo, tx)

        assertFailsWith<AppError.Forbidden> { requireMembership.requireOwner(user, workspace) }
    }
}
