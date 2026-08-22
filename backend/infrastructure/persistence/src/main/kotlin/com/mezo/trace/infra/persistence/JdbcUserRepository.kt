package com.mezo.trace.infra.persistence

import com.mezo.trace.application.port.UserRepository
import com.mezo.trace.domain.Email
import com.mezo.trace.domain.User
import com.mezo.trace.domain.UserId
import java.sql.ResultSet

class JdbcUserRepository(private val sql: SqlConnections) : UserRepository {

    override fun insert(user: User) {
        sql.current().prepareStatement(
            """
            INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, java.util.UUID.fromString(user.id.value))
            ps.setString(2, user.email.value)
            ps.setNullableString(3, user.displayName)
            ps.setString(4, user.passwordHash)
            ps.setInstant(5, user.createdAt)
            ps.setInstant(6, user.updatedAt)
            ps.executeUpdate()
        }
    }

    override fun findByEmail(email: Email): User? =
        queryOne("SELECT * FROM users WHERE lower(email) = ?", email.value)

    override fun findById(id: UserId): User? {
        val uuid = runCatching { java.util.UUID.fromString(id.value) }.getOrNull() ?: return null
        return queryOne("SELECT * FROM users WHERE id = ?", uuid)
    }

    override fun delete(id: UserId) {
        sql.current().prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
            ps.setObject(1, java.util.UUID.fromString(id.value))
            ps.executeUpdate()
        }
    }

    private fun queryOne(query: String, param: Any): User? {
        sql.current().prepareStatement(query).use { ps ->
            ps.setObject(1, param)
            ps.executeQuery().use { rs -> if (rs.next()) return rs.toUser() }
        }
        return null
    }

    private fun ResultSet.toUser(): User = User(
        id = UserId(getObject("id", java.util.UUID::class.java).toString()),
        email = Email(getString("email")),
        displayName = getObject("display_name") as String?,
        passwordHash = getString("password_hash"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )
}
