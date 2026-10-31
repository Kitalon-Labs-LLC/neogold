package com.kitalonlabs.neogold.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * In-memory [MasterKeyProvider] for unit testing and offline verification.
 * Implements actual AES-256-GCM encryption/decryption with real GCM authentication tags,
 * matching [AndroidMasterKeyProvider]'s binary wire format (12-byte IV + ciphertext + tag).
 */
class FakeMasterKeyProvider : MasterKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()

    var simulateCorruptedDecryption: Boolean = false

    override fun getOrCreateMasterKey(alias: String): SecretKey {
        return keys.getOrPut(alias) {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, SecureRandom())
            keyGen.generateKey()
        }
    }

    override fun wrap(alias: String, plaintext: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterKey(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv ?: error("Cipher IV was null")
        val ciphertext = cipher.doFinal(plaintext)

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    @Suppress("TooGenericExceptionCaught") // any key store failure is key loss: fail closed with a typed exception
    override fun unwrap(alias: String, wrappedData: ByteArray): ByteArray {
        if (!hasMasterKey(alias)) {
            throw KeystoreLossException("Master key '$alias' missing from Keystore")
        }
        if (simulateCorruptedDecryption) {
            throw KeystoreLossException("Simulated Keystore decryption corruption")
        }
        if (wrappedData.size < GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
            throw KeystoreLossException("Wrapped data too short (${wrappedData.size} bytes)")
        }

        val masterKey = keys[alias] ?: throw KeystoreLossException("Master key '$alias' missing from Keystore")
        val iv = wrappedData.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = wrappedData.copyOfRange(GCM_IV_LENGTH_BYTES, wrappedData.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw KeystoreLossException("Failed to unwrap data with master key '$alias'", e)
        }
    }

    override fun deleteMasterKey(alias: String) {
        keys.remove(alias)
    }

    override fun hasMasterKey(alias: String): Boolean {
        return keys.containsKey(alias)
    }

    companion object {
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }
}
