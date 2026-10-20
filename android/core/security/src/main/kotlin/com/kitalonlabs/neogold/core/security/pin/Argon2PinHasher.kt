package com.kitalonlabs.neogold.core.security.pin

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Argon2id profile configuration per doc 8 Section 6.2.
 */
enum class Argon2Profile(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int
) {
    /**
     * Primary OWASP profile: m = 19 MiB (19456 KiB), t = 2, p = 1.
     */
    STANDARD(memoryKiB = 19 * 1024, iterations = 2, parallelism = 1),

    /**
     * Fallback OWASP profile for 2 GB / slower devices if standard takes > 1 s:
     * m = 12 MiB (12288 KiB), t = 3, p = 1.
     */
    CONSTRAINED(memoryKiB = 12 * 1024, iterations = 3, parallelism = 1)
}

/**
 * Hashes research PINs using Argon2id then HMAC-SHA256 with a device-bound Keystore key (doc 8 Section 6.2).
 */
class Argon2PinHasher(
    private val hmacKeyProvider: PinHmacKeyProvider = KeystorePinHmacKeyProvider(),
    private val profile: Argon2Profile = Argon2Profile.STANDARD,
    private val secureRandom: SecureRandom = SecureRandom()
) {

    companion object {
        const val SALT_LENGTH_BYTES = 16
        const val HASH_OUTPUT_BYTES = 32
    }

    /**
     * Generates a 16-byte cryptographically secure random salt.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Hashes a PIN using Argon2id with the configured profile and salt,
     * then binds the output to the device via HMAC-SHA256.
     */
    fun hashPin(pin: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH_BYTES) { "Salt must be $SALT_LENGTH_BYTES bytes" }

        val argon2Params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(profile.memoryKiB)
            .withIterations(profile.iterations)
            .withParallelism(profile.parallelism)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(argon2Params)

        val argon2Output = ByteArray(HASH_OUTPUT_BYTES)
        generator.generateBytes(pin.toByteArray(Charsets.UTF_8), argon2Output, 0, argon2Output.size)

        // Pass Argon2id output through device-bound HMAC-SHA256
        return hmacKeyProvider.computeHmac(argon2Output)
    }

    /**
     * Computes only the Argon2id stage (without device HMAC).
     * Used for testing TC-RSH-06 (proving that Argon2id output alone cannot verify without Keystore).
     */
    fun computeArgon2Only(pin: String, salt: ByteArray): ByteArray {
        val argon2Params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(profile.memoryKiB)
            .withIterations(profile.iterations)
            .withParallelism(profile.parallelism)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(argon2Params)

        val argon2Output = ByteArray(HASH_OUTPUT_BYTES)
        generator.generateBytes(pin.toByteArray(Charsets.UTF_8), argon2Output, 0, argon2Output.size)
        return argon2Output
    }

    /**
     * Constant-time verification of a candidate PIN against stored salt and hash.
     */
    fun verifyPin(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean {
        val computedHash = hashPin(pin, salt)
        return MessageDigest.isEqual(computedHash, expectedHash)
    }
}
