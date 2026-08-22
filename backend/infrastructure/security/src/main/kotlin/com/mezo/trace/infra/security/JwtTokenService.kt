package com.mezo.trace.infra.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.IssuedAccessToken
import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.domain.UserId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * HS256 JWT access tokens + opaque refresh-token material.
 *
 * The application layer only sees the TokenIssuer port; swapping to an
 * external OIDC provider replaces this class, nothing else (ADR-015).
 */
class JwtTokenService(
    secret: ByteArray,
    private val clock: ClockPort,
    private val accessTokenTtl: Duration,
    private val issuer: String = "trace",
) : TokenIssuer {

    private val algorithm: Algorithm = Algorithm.HMAC256(secret)
    private val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .build()
    private val random = SecureRandom()

    init {
        require(secret.size >= MIN_SECRET_BYTES) {
            "JWT secret must be at least $MIN_SECRET_BYTES bytes"
        }
    }

    override fun issueAccessToken(userId: UserId): IssuedAccessToken {
        val now = clock.now()
        val expiresAt = now.plus(accessTokenTtl)
        val token = JWT.create()
            .withIssuer(issuer)
            .withSubject(userId.value)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        return IssuedAccessToken(token, expiresAt)
    }

    override fun generateRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    override fun validateAccessToken(token: String): UserId? {
        return try {
            val decoded = verifier.verify(token)
            // Expiry is enforced against the injected clock so validation is
            // deterministic and consistent with issuance.
            val expiresAt = decoded.expiresAt?.toInstant()
            if (expiresAt != null && !expiresAt.isAfter(clock.now())) return null
            UserId(decoded.subject)
        } catch (_: Exception) {
            null
        }
    }

    fun expiresAtOf(now: Instant): Instant = now.plus(accessTokenTtl)

    private companion object {
        const val REFRESH_TOKEN_BYTES = 32
        const val MIN_SECRET_BYTES = 32
    }
}
