package com.kitalonlabs.neogold.core.security

import android.content.Context
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKeyManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException

interface FileKeysetProvider {
    fun getStreamingAead(): StreamingAead
    fun verifyKeysetDecryption()
    fun deleteKeyset()
    fun hasWrappedKeyset(): Boolean
}

class DefaultFileKeysetProvider(
    private val context: Context,
    private val masterKeyProvider: MasterKeyProvider,
    private val keyDirectory: File = File(context.filesDir, "security")
) : FileKeysetProvider {

    private val wrappedKeysetFile: File
        get() = File(keyDirectory, "file_keyset.wrapped")

    @Volatile
    private var cachedStreamingAead: StreamingAead? = null
    private val lock = Any()

    init {
        try {
            StreamingAeadConfig.register()
        } catch (_: Exception) {}
    }

    override fun getStreamingAead(): StreamingAead {
        return synchronized(lock) {
            cachedStreamingAead ?: loadOrGenerateKeyset().also { cachedStreamingAead = it }
        }
    }

    override fun verifyKeysetDecryption() {
        if (wrappedKeysetFile.exists()) {
            val wrappedBytes = wrappedKeysetFile.readBytes()
            masterKeyProvider.unwrap(MasterKeyProvider.ALIAS_FILE_MASTER, wrappedBytes)
        }
    }

    private fun loadOrGenerateKeyset(): StreamingAead {
        StreamingAeadConfig.register()

        if (wrappedKeysetFile.exists()) {
            val wrappedBytes = wrappedKeysetFile.readBytes()
            val cleartextBytes = masterKeyProvider.unwrap(MasterKeyProvider.ALIAS_FILE_MASTER, wrappedBytes)
            val keysetHandle = try {
                CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(cleartextBytes))
            } catch (e: Exception) {
                throw KeystoreLossException("Failed to parse unwrapped Tink keyset", e)
            }
            return keysetHandle.getPrimitive(StreamingAead::class.java)
        }

        // Generate fresh AES256_GCM_HKDF_4KB streaming keyset
        val keysetHandle = KeysetHandle.generateNew(AesGcmHkdfStreamingKeyManager.aes256GcmHkdf4KBTemplate())
        val baos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(keysetHandle, BinaryKeysetWriter.withOutputStream(baos))
        val cleartextBytes = baos.toByteArray()

        val wrappedBytes = masterKeyProvider.wrap(MasterKeyProvider.ALIAS_FILE_MASTER, cleartextBytes)
        AtomicFileStorage.writeBytes(wrappedKeysetFile, wrappedBytes)

        return keysetHandle.getPrimitive(StreamingAead::class.java)
    }

    override fun deleteKeyset() {
        synchronized(lock) {
            cachedStreamingAead = null
            if (wrappedKeysetFile.exists()) {
                wrappedKeysetFile.delete()
            }
            masterKeyProvider.deleteMasterKey(MasterKeyProvider.ALIAS_FILE_MASTER)
        }
    }

    override fun hasWrappedKeyset(): Boolean {
        return wrappedKeysetFile.exists()
    }
}
