package com.mezo.trace.infra.security

import java.time.Duration

/**
 * Security configuration from environment.
 *
 * - `JWT_SECRET`: HMAC secret, required in production, minimum 32 bytes.
 *   In non-production a deterministic development secret is derived so local
 *   `./gradlew :server:api:run` works without setup; production
 *   (`TRACE_ENV=production`) refuses to start without a real secret.
 */
data class SecurityConfig(
    val jwtSecret: ByteArray,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
) {

    companion object {

        private const val MIN_SECRET_BYTES = 32

        fun load(env: Map<String, String> = System.getenv()): SecurityConfig {
            val isProduction = env["TRACE_ENV"] == "production"
            val secret = env["JWT_SECRET"]
                ?: if (isProduction) {
                    throw IllegalStateException("JWT_SECRET is required in production")
                } else {
                    "dev-only-insecure-secret-change-me-0123456789"
                }
            val bytes = secret.toByteArray(Charsets.UTF_8)
            require(bytes.size >= MIN_SECRET_BYTES) {
                "JWT_SECRET must be at least $MIN_SECRET_BYTES bytes"
            }

            return SecurityConfig(
                jwtSecret = bytes,
                accessTokenTtl = Duration.ofMinutes(
                    env["ACCESS_TOKEN_TTL_MINUTES"]?.toLongOrNull() ?: 15L,
                ),
                refreshTokenTtl = Duration.ofDays(
                    env["REFRESH_TOKEN_TTL_DAYS"]?.toLongOrNull() ?: 30L,
                ),
            )
        }
    }
}
