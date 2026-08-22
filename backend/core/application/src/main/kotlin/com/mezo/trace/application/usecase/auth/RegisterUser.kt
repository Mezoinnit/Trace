package com.mezo.trace.application.usecase.auth

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.PasswordHasher
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.UserRepository
import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.domain.Email
import com.mezo.trace.domain.PasswordPolicy
import com.mezo.trace.domain.Role
import com.mezo.trace.domain.User
import com.mezo.trace.domain.UserId
import com.mezo.trace.domain.ValidationException
import com.mezo.trace.domain.Workspace
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.domain.WorkspaceMember

class RegisterUser(
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val memberships: WorkspaceMembershipRepository,
    private val passwordHasher: PasswordHasher,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val clock: com.mezo.trace.application.port.ClockPort,
    private val sessionIssuer: AuthSessionIssuer,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    data class Registered(
        val user: User,
        val workspaceId: WorkspaceId,
        val session: AuthSessionIssuer.Session,
    )

    fun execute(emailRaw: String, password: String, displayNameRaw: String?): Registered {
        return tx.run {
            val email = Email.create(emailRaw)
            PasswordPolicy.validate(password)
            val displayName = normalizeDisplayName(displayNameRaw)

            if (users.findByEmail(email) != null) {
                throw AppError.Conflict("email already registered")
            }

            val now = clock.now()
            val user = User(
                id = UserId(idGenerator()),
                email = email,
                displayName = displayName,
                passwordHash = passwordHasher.hash(password),
                createdAt = now,
                updatedAt = now,
            )
            users.insert(user)

            val workspaceId = WorkspaceId(idGenerator())
            workspaces.insert(
                Workspace(
                    id = workspaceId,
                    name = "Personal Workspace",
                    createdBy = user.id,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            memberships.insert(
                WorkspaceMember(workspaceId, user.id, Role.OWNER, now),
            )

            audit.append(
                AuditEntry(
                    action = "USER_REGISTERED",
                    actorType = ActorType.USER,
                    actorId = user.id.value,
                    resourceType = "user",
                    resourceId = user.id.value,
                ),
            )
            audit.append(
                AuditEntry(
                    action = "WORKSPACE_CREATED",
                    actorType = ActorType.USER,
                    actorId = user.id.value,
                    workspaceId = workspaceId,
                    resourceType = "workspace",
                    resourceId = workspaceId.value,
                    metadata = mapOf("name" to "Personal Workspace"),
                ),
            )

            sessionIssuer.issue(user).let { session -> Registered(user, workspaceId, session) }
        }
    }

    private fun normalizeDisplayName(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed.length > 100) {
            throw ValidationException("display_name", "display_name must be at most 100 characters")
        }
        return trimmed
    }
}
