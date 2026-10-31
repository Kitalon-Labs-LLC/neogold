package com.kitalonlabs.neogold.core.security.export

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.Bytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * The encrypting side of the research export (doc 8 Section 5.2, docs/specs/export-format.md). The phone only encrypts:
 * there is no function here that unwraps a data key or decrypts a stream, and none that handles the study's private key.
 * That side lives in the offline decrypt tool (`:neogold-decrypt`), and the app's tests use it to check this side.
 *
 * Everything except [createSession], [sha256Hex] and [CONTEXT_INFO] is internal: the data key is reachable only through an
 * [ExportSession], which cannot serialize it.
 */
object ExportCryptoEngine {

    val CONTEXT_INFO: ByteArray = "neogold-export-v1".toByteArray(StandardCharsets.UTF_8)
    private const val BUFFER_SIZE = 8192
    private const val X25519_KEY_BYTES = 32
    private const val HPKE_KEY_ID = 1

    /** HPKE (X25519, HKDF-SHA256, AES-256-GCM) with no key prefix: the wrap format of export-format v1. */
    private val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    init {
        HybridConfig.register()
        StreamingAeadConfig.register()
    }

    /** A fresh ephemeral AES-256-GCM-HKDF (4 KB segments) streaming keyset for the files of one export. */
    internal fun generateDataKeysetHandle(): KeysetHandle =
        KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)

    /**
     * Serializes the data keyset and wraps it with Tink HPKE to the 32-byte raw X25519 public key from the signed study
     * profile. The serialized key exists only inside this function and is zeroed before it returns.
     */
    internal fun wrapDataKey(dataKeysetHandle: KeysetHandle, exportPublicKeyBytes: ByteArray): String {
        require(exportPublicKeyBytes.size == X25519_KEY_BYTES) {
            "Export public key must be exactly $X25519_KEY_BYTES bytes (X25519), got ${exportPublicKeyBytes.size}"
        }
        val publicKey = HpkePublicKey.create(HPKE_PARAMETERS, Bytes.copyFrom(exportPublicKeyBytes), null)
        val publicHandle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(publicKey).withFixedId(HPKE_KEY_ID).makePrimary())
            .build()
        val encryptor = publicHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

        val cleartextDataKeyBytes = TinkProtoKeysetFormat.serializeKeyset(
            dataKeysetHandle,
            InsecureSecretKeyAccess.get()
        )
        try {
            return Base64.getEncoder().encodeToString(encryptor.encrypt(cleartextDataKeyBytes, CONTEXT_INFO))
        } finally {
            cleartextDataKeyBytes.fill(0)
        }
    }

    internal fun getStreamingAead(dataKeysetHandle: KeysetHandle): StreamingAead =
        dataKeysetHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)

    /** Encrypts an [InputStream] to an [OutputStream] using Tink Streaming AEAD. */
    internal fun encryptStream(
        streamingAead: StreamingAead,
        inputStream: InputStream,
        outputStream: OutputStream,
        associatedData: ByteArray = ByteArray(0)
    ) {
        streamingAead.newEncryptingStream(outputStream, associatedData).use { encryptingStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                encryptingStream.write(buffer, 0, bytesRead)
            }
            encryptingStream.flush()
        }
    }

    internal fun encryptBytes(
        streamingAead: StreamingAead,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        val bais = ByteArrayInputStream(plaintext)
        val baos = ByteArrayOutputStream()
        encryptStream(streamingAead, bais, baos, associatedData)
        return baos.toByteArray()
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun createSession(): ExportSession {
        return ExportSession(generateDataKeysetHandle())
    }
}

/** The data key of one export, held in memory for the length of that export and never written anywhere. */
class ExportSession internal constructor(
    private val keysetHandle: KeysetHandle
) {
    private val streamingAead: StreamingAead = ExportCryptoEngine.getStreamingAead(keysetHandle)

    fun encryptBytes(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return ExportCryptoEngine.encryptBytes(streamingAead, plaintext, associatedData)
    }

    fun encryptStream(inputStream: InputStream, outputStream: OutputStream, associatedData: ByteArray = ByteArray(0)) {
        ExportCryptoEngine.encryptStream(streamingAead, inputStream, outputStream, associatedData)
    }

    fun wrapDataKey(exportPublicKeyBytes: ByteArray): String {
        return ExportCryptoEngine.wrapDataKey(keysetHandle, exportPublicKeyBytes)
    }

    // There is deliberately no way to serialize this key: the data key of an export lives in memory only, and leaves the
    // phone only inside the HPKE wrap addressed to the offline study key (BUG-2026-038).
}
