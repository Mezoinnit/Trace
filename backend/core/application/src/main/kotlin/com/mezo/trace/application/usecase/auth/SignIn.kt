package com.mezo.trace.application.usecase.auth

import com.mezo.trace.application.AppError
import com.mezo.trace.application.model.ActorType
import com.mezo.trace.application.model.AuditEntry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.PasswordHasher
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.UserRepository
import com.mezo.trace.domain.Email
import com.mezo.trace.domain.ValidationException

class SignIn(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val audit: AuditLogRepository,
    private val tx: TxRunner,
    private val sessionIssuer: AuthSessionIssuer,
) {

    data class SignedIn(
        val user: com.mezo.trace.domain.User,
        val session: AuthSessionIssuer.Session,
    )

    fun execute(emailRaw: String, password: String): SignedIn {
        return tx.run {
            val user = try {
                users.findByEmail(Email.create(emailRaw))
            } catch (_: ValidationException) {
                // Unparseable email on sign-in is an authentication failure,
                // not a validation error (do not leak account existence).
                null
            } ?: throw AppError.InvalidCredentials()

            if (!passwordHasher.verify(password, user.passwordHash)) {
                throw AppError.InvalidCredentials()
            }

            val session = sessionIssuer.issue(user)
            audit.append(
                AuditEntry(
                    action = "USER_SIGNED_IN",
                    actorType = ActorType.USER,
                    actorId = user.id.value,
                    resourceType = "user",
                    resourceId = user.id.value,
                ),
            )
            SignedIn(user, session)
        }
    }
}
