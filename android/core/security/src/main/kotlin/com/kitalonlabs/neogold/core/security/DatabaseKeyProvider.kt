package com.kitalonlabs.neogold.core.security

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Arrays

interface DatabaseKeyProvider {
    /**
     * Returns the raw 256-bit SQLCipher key formatted in the raw-key syntax:
     * `x'<64 hex characters>'` as UTF-8 bytes.
     *
     * This bypasses SQLCipher's 256,000 PBKDF2 iterations on open since the key
     * is already 256-bit cryptographic random.
     */
    fun getSqlCipherPassphrase(): ByteArray

    /**
     * Returns a copy of the 32-byte (256-bit) raw key.
     */
    fun getRawKey(): ByteArray

    /**
     * Verifies that the wrapped key on disk can be decrypted by the master key.
     * Bypasses in-memory cache to detect Keystore loss.
     */
    fun verifyKeyDecryption()

    /**
     * Wipes the wrapped key file from app storage and removes the master key from Keystore.
     */
    fun deleteKey()

    /**
     * Returns true if wrapped key file exists on disk.
     */
    fun hasWrappedKey(): Boolean
}

class DefaultDatabaseKeyProvider(
    private val context: Context,
    private val masterKeyProvider: MasterKeyProvider,
    private val keyDirectory: File = File(context.filesDir, "security")
) : DatabaseKeyProvider {

    private val wrappedKeyFile: File
        get() = File(keyDirectory, "db_key.wrapped")

    @Volatile
    private var cachedRawKey: ByteArray? = null
    private val lock = Any()

    override fun getRawKey(): ByteArray {
        return synchronized(lock) {
            val key = cachedRawKey ?: loadOrGenerateKey().also { cachedRawKey = it }
            key.copyOf()
        }
    }

    override fun getSqlCipherPassphrase(): ByteArray {
        val raw = getRawKey()
        try {
            val hex = raw.joinToString("") { "%02x".format(it) }
            return "x'$hex'".toByteArray(Charsets.UTF_8)
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    override fun verifyKeyDecryption() {
        if (wrappedKeyFile.exists()) {
            val wrappedBytes = wrappedKeyFile.readBytes()
            val unwrapped = masterKeyProvider.unwrap(MasterKeyProvider.ALIAS_DB_MASTER, wrappedBytes)
            Arrays.fill(unwrapped, 0.toByte())
        }
    }

    private fun loadOrGenerateKey(): ByteArray {
        if (wrappedKeyFile.exists()) {
            val wrappedBytes = wrappedKeyFile.readBytes()
            val unwrapped = masterKeyProvider.unwrap(MasterKeyProvider.ALIAS_DB_MASTER, wrappedBytes)
            if (unwrapped.size != KEY_SIZE_BYTES) {
                Arrays.fill(unwrapped, 0.toByte())
                throw KeystoreLossException(
                    "Unwrapped SQLCipher database key has invalid size (${unwrapped.size} bytes; expected $KEY_SIZE_BYTES)"
                )
            }
            return unwrapped
        }

        // Generate fresh 256-bit (32 bytes) random key
        val randomKey = ByteArray(KEY_SIZE_BYTES)
        SecureRandom().nextBytes(randomKey)

        try {
            val wrapped = masterKeyProvider.wrap(MasterKeyProvider.ALIAS_DB_MASTER, randomKey)
            AtomicFileStorage.writeBytes(wrappedKeyFile, wrapped)
            return randomKey.copyOf()
        } finally {
            Arrays.fill(randomKey, 0.toByte())
        }
    }

    override fun deleteKey() {
        synchronized(lock) {
            cachedRawKey?.let { Arrays.fill(it, 0.toByte()) }
            cachedRawKey = null

            if (wrappedKeyFile.exists()) {
                wrappedKeyFile.delete()
            }
            masterKeyProvider.deleteMasterKey(MasterKeyProvider.ALIAS_DB_MASTER)
        }
    }

    override fun hasWrappedKey(): Boolean {
        return wrappedKeyFile.exists()
    }

    companion object {
        const val KEY_SIZE_BYTES = 32 // 256-bit
    }
}
