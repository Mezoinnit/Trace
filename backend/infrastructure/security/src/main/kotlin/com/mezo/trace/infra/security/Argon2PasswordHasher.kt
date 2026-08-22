package com.mezo.trace.infra.security

import com.mezo.trace.application.port.PasswordHasher
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing (BouncyCastle).
 *
 * Encoded form: `$argon2id$v=19$m=<mem>,t=<iters>,p=<par>$<saltB64>$<hashB64>`
 * Parameters travel with the hash so they can be strengthened later without
 * breaking existing hashes.
 */
class Argon2PasswordHasher(
    private val memoryKiB: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
    private val hashLengthBytes: Int = 32,
) : PasswordHasher {

    private val random = SecureRandom()

    override fun hash(plain: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = derive(plain.toCharArray(), salt)
        return "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism" +
            "\$${b64(salt)}\$${b64(digest)}"
    }

    override fun verify(plain: String, encoded: String): Boolean {
        val parts = encoded.split("$").filter { it.isNotBlank() }
        if (parts.size != 5 || parts[0] != "argon2id") return false

        val params = parts[2].split(",")
        val memory = params.firstOrNull { it.startsWith("m=") }?.removePrefix("m=")?.toIntOrNull()
            ?: return false
        val iterationsPart = params.firstOrNull { it.startsWith("t=") }?.removePrefix("t=")?.toIntOrNull()
            ?: return false
        val parallelismPart = params.firstOrNull { it.startsWith("p=") }?.removePrefix("p=")?.toIntOrNull()
            ?: return false

        return try {
            val salt = unb64(parts[3])
            val expected = unb64(parts[4])
            val actual = ByteArray(expected.size)
            Argon2BytesGenerator().apply {
                init(
                    argon2Params(memory, iterationsPart, parallelismPart),
                )
            }.generateBytes(plain.toCharArray(), actual)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val out = ByteArray(hashLengthBytes)
        Argon2BytesGenerator().apply {
            init(argon2Params(memoryKiB, iterations, parallelism))
        }.generateBytes(password, out)
        return out
    }

    private fun argon2Params(memoryKiB: Int, iterations: Int, parallelism: Int): Argon2Parameters =
        Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(value: String): ByteArray =
        Base64.getDecoder().decode(value)

    private companion object {
        const val SALT_BYTES = 16
    }
}
