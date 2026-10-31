package com.kitalonlabs.neogold.core.security

import android.content.Context
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File

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

    @Suppress("TooGenericExceptionCaught") // any key store failure is key loss: fail closed with a typed exception
    private fun loadOrGenerateKeyset(): StreamingAead {
        StreamingAeadConfig.register()

        if (wrappedKeysetFile.exists()) {
            val wrappedBytes = wrappedKeysetFile.readBytes()
            val cleartextBytes = masterKeyProvider.unwrap(MasterKeyProvider.ALIAS_FILE_MASTER, wrappedBytes)
            val keysetHandle = try {
                TinkProtoKeysetFormat.parseKeyset(cleartextBytes, InsecureSecretKeyAccess.get())
            } catch (e: Exception) {
                throw KeystoreLossException("Failed to parse unwrapped Tink keyset", e)
            } finally {
                cleartextBytes.fill(0)
            }
            return keysetHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        }

        // Generate fresh AES256_GCM_HKDF_4KB streaming keyset
        val keysetHandle = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
        val cleartextBytes = TinkProtoKeysetFormat.serializeKeyset(keysetHandle, InsecureSecretKeyAccess.get())
        val wrappedBytes = try {
            masterKeyProvider.wrap(MasterKeyProvider.ALIAS_FILE_MASTER, cleartextBytes)
        } finally {
            cleartextBytes.fill(0)
        }
        AtomicFileStorage.writeBytes(wrappedKeysetFile, wrappedBytes)

        return keysetHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
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
