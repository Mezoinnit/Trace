package com.mezo.trace.application.port

/**
 * Password hashing port (argon2id in infrastructure). Pure interface so the
 * application layer never depends on a crypto library.
 */
interface PasswordHasher {
    fun hash(plain: String): String
    fun verify(plain: String, hash: String): Boolean
}
