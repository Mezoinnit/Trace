package com.mezo.trace.application

import com.mezo.trace.application.model.RefreshTokenRecord
import com.mezo.trace.application.model.TokenStatus
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.IssuedAccessToken
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.UserRepository
import com.mezo.trace.application.usecase.auth.AuthSessionIssuer
import com.mezo.trace.application.usecase.auth.RefreshSession
import com.mezo.trace.domain.Email
import com.mezo.trace.domain.User
import com.mezo.trace.domain.UserId
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.assertTrue

/** Deterministic fake token issuer (no crypto; format-encoded identity). */
private class FakeTokenIssuer : TokenIssuer {
    private var counter = 0
    override fun issueAccessToken(userId: UserId) =
        IssuedAccessToken("acc-${userId.value}-${counter++}", Instant.ofEpochSecond(4_102_444_800))

    override fun generateRefreshToken(): String = "raw-refresh-${counter++}"
    override fun hash(token: String): String = "h:$token"
    override fun validateAccessToken(token: String): UserId? =
        token.removePrefix("acc-").substringBeforeLast("-").let { UserId(it) }
}

private class InMemoryRefreshTokens : RefreshTokenRepository {
    val rows = mutableListOf<RefreshTokenRecord>()

    override fun insert(record: RefreshTokenRecord) {
        rows += record
    }

    override fun findByHash(tokenHash: String): RefreshTokenRecord? =
        rows.firstOrNull { it.tokenHash == tokenHash }

    override fun markReplaced(id: UUID, now: Instant) {
        rows.replaceAll {
            if (it.id == id && it.status == TokenStatus.ACTIVE) it.copy(status = TokenStatus.REPLACED) else it
        }
    }

    override fun revokeFamily(familyId: UUID, now: Instant) {
        rows.replaceAll {
            if (it.familyId == familyId && it.status != TokenStatus.REVOKED) {
                it.copy(status = TokenStatus.REVOKED)
            } else {
                it
            }
        }
    }

    override fun revokeAllForUser(userId: UserId, now: Instant) {
        rows.replaceAll {
            if (it.userId == userId && it.status != TokenStatus.REVOKED) {
                it.copy(status = TokenStatus.REVOKED)
            } else {
                it
            }
        }
    }
}

private class InMemoryUsers(var user: User?) : UserRepository {
    val deleted = mutableListOf<UserId>()

    override fun insert(user: User) {
        this.user = user
    }

    override fun findByEmail(email: Email): User? = user?.takeIf { it.email == email }
    override fun findById(id: UserId): User? = user?.takeIf { it.id == id }
    override fun delete(id: UserId) {
        if (user?.id == id) user = null
        deleted += id
    }
}

class RecordingAudit : AuditLogRepository {
    val actions = mutableListOf<String>()
    override fun append(entry: com.mezo.trace.application.model.AuditEntry) {
        actions += entry.action
    }
    override fun listForResource(
        resourceType: String,
        resourceId: String,
    ): List<com.mezo.trace.application.port.EventHistoryEntry> = emptyList()
}

private class InlineTx : TxRunner {
    override fun <T> run(block: () -> T): T = block()
}

private class TestClock(private var current: Instant = Instant.now()) :
    com.mezo.trace.application.port.ClockPort {
    override fun now(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

class RefreshSessionTest {

    private val clock = TestClock()
    private val tokens = InMemoryRefreshTokens()
    private val issuer = FakeTokenIssuer()
    private val audit = RecordingAudit()

    private val userId = UserId("99999999-9999-9999-9999-999999999999")
    private val user = User(
        id = userId,
        email = Email.create("user@example.com"),
        displayName = null,
        passwordHash = "hash",
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private val refreshTtl = Duration.ofDays(30)

    private fun useCase() = RefreshSession(
        refreshTokens = tokens,
        users = InMemoryUsers(user),
        audit = audit,
        tx = InlineTx(),
        tokenIssuer = issuer,
        clock = clock,
        refreshTtl = refreshTtl,
    )

    private fun seedActiveToken(raw: String): RefreshTokenRecord {
        val record = RefreshTokenRecord(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = issuer.hash(raw),
            familyId = UUID.randomUUID(),
            status = TokenStatus.ACTIVE,
            expiresAt = clock.now().plus(refreshTtl),
            createdAt = clock.now(),
        )
        tokens.insert(record)
        return record
    }

    @Test
    fun `valid token rotates within the same family`() {
        val original = seedActiveToken("raw-1")

        val refreshed = useCase().execute("raw-1")

        assertEquals(original.familyId, refreshed.session.refreshToken.let { raw ->
            tokens.findByHash(issuer.hash(raw))!!.familyId
        })
        assertEquals(TokenStatus.REPLACED, tokens.findByHash(original.tokenHash)!!.status)
    }

    @Test
    fun `reuse of a replaced token revokes the whole family`() {
        val original = seedActiveToken("raw-1")
        useCase().execute("raw-1") // rotate → original now REPLACED

        assertFailsWith<AppError.Unauthenticated> { useCase().execute("raw-1") }

        assertTrue(tokens.rows.all { it.familyId == original.familyId && it.status == TokenStatus.REVOKED })
        assertTrue(audit.actions.contains("REFRESH_TOKEN_REUSE"))
    }

    @Test
    fun `unknown tokens are rejected`() {
        assertFailsWith<AppError.Unauthenticated> { useCase().execute("never-issued") }
    }

    @Test
    fun `expired tokens are rejected`() {
        seedActiveToken("raw-expired")
        clock.advance(Duration.ofDays(31))

        assertFailsWith<AppError.Unauthenticated> { useCase().execute("raw-expired") }
    }

    @Test
    fun `blank tokens are rejected`() {
        assertFailsWith<AppError.Unauthenticated> { useCase().execute("") }
    }
}
