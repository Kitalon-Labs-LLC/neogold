package com.kitalonlabs.neogold.core.security.export

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkeParams
import com.google.crypto.tink.proto.HpkePrivateKey
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKeyManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.X25519
import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

object ExportCryptoEngine {

    val CONTEXT_INFO: ByteArray = "neogold-export-v1".toByteArray(StandardCharsets.UTF_8)
    private const val BUFFER_SIZE = 8192

    init {
        try {
            HybridConfig.register()
            StreamingAeadConfig.register()
        } catch (_: Exception) {}
    }

    /**
     * Generates a fresh ephemeral Tink streaming AEAD keyset for encrypting export files.
     */
    fun generateDataKeysetHandle(): KeysetHandle {
        StreamingAeadConfig.register()
        return KeysetHandle.generateNew(AesGcmHkdfStreamingKeyManager.aes256GcmHkdf4KBTemplate())
    }

    /**
     * Serializes a data keyset handle and wraps it with Tink HPKE (X25519, HKDF-SHA256, AES-256-GCM)
     * using the 32-byte raw X25519 public key from the signed study profile.
     */
    fun wrapDataKey(dataKeysetHandle: KeysetHandle, exportPublicKeyBytes: ByteArray): String {
        require(exportPublicKeyBytes.size == 32) {
            "Export public key must be exactly 32 bytes (X25519), got ${exportPublicKeyBytes.size}"
        }
        HybridConfig.register()

        // 1. Serialize data keyset to cleartext binary
        val baos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(dataKeysetHandle, BinaryKeysetWriter.withOutputStream(baos))
        val cleartextDataKeyBytes = baos.toByteArray()

        // 2. Build Tink HPKE public key keyset
        val hpkePubKeyProto = HpkePublicKey.newBuilder()
            .setVersion(0)
            .setParams(
                HpkeParams.newBuilder()
                    .setKem(HpkeKem.DHKEM_X25519_HKDF_SHA256)
                    .setKdf(HpkeKdf.HKDF_SHA256)
                    .setAead(HpkeAead.AES_256_GCM)
                    .build()
            )
            .setPublicKey(ByteString.copyFrom(exportPublicKeyBytes))
            .build()

        val pubKeyData = KeyData.newBuilder()
            .setTypeUrl("type.googleapis.com/google.crypto.tink.HpkePublicKey")
            .setValue(hpkePubKeyProto.toByteString())
            .setKeyMaterialType(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)
            .build()

        val pubKeyset = Keyset.newBuilder()
            .setPrimaryKeyId(1)
            .addKey(
                Keyset.Key.newBuilder()
                    .setKeyData(pubKeyData)
                    .setStatus(KeyStatusType.ENABLED)
                    .setKeyId(1)
                    .setOutputPrefixType(OutputPrefixType.RAW)
                    .build()
            )
            .build()

        val pubHandle = CleartextKeysetHandle.fromKeyset(pubKeyset)
        val encryptor = pubHandle.getPrimitive(HybridEncrypt::class.java)

        // 3. Encrypt the data keyset using HPKE
        val wrappedBytes = encryptor.encrypt(cleartextDataKeyBytes, CONTEXT_INFO)
        return Base64.getEncoder().encodeToString(wrappedBytes)
    }

    /**
     * Unwraps the data keyset using Tink HPKE with the offline 32-byte raw X25519 private key.
     */
    fun unwrapDataKey(wrappedDataKeyB64: String, exportPrivateKeyBytes: ByteArray): KeysetHandle {
        require(exportPrivateKeyBytes.size == 32) {
            "Export private key must be exactly 32 bytes (X25519), got ${exportPrivateKeyBytes.size}"
        }
        HybridConfig.register()

        val ciphertext = try {
            Base64.getDecoder().decode(wrappedDataKeyB64.trim())
        } catch (e: Exception) {
            throw DecryptionException("Malformed base64 in wrapped data key header", e)
        }

        // Derive public key from private key using RFC 7748 X25519 scalar multiplication
        val derivedPublicBytes = try {
            X25519.publicFromPrivate(exportPrivateKeyBytes)
        } catch (e: Exception) {
            throw DecryptionException("Invalid X25519 private key", e)
        }

        val hpkePubKeyProto = HpkePublicKey.newBuilder()
            .setVersion(0)
            .setParams(
                HpkeParams.newBuilder()
                    .setKem(HpkeKem.DHKEM_X25519_HKDF_SHA256)
                    .setKdf(HpkeKdf.HKDF_SHA256)
                    .setAead(HpkeAead.AES_256_GCM)
                    .build()
            )
            .setPublicKey(ByteString.copyFrom(derivedPublicBytes))
            .build()

        val hpkePrivKeyProto = HpkePrivateKey.newBuilder()
            .setVersion(0)
            .setPublicKey(hpkePubKeyProto)
            .setPrivateKey(ByteString.copyFrom(exportPrivateKeyBytes))
            .build()

        val privKeyData = KeyData.newBuilder()
            .setTypeUrl("type.googleapis.com/google.crypto.tink.HpkePrivateKey")
            .setValue(hpkePrivKeyProto.toByteString())
            .setKeyMaterialType(KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE)
            .build()

        val privKeyset = Keyset.newBuilder()
            .setPrimaryKeyId(1)
            .addKey(
                Keyset.Key.newBuilder()
                    .setKeyData(privKeyData)
                    .setStatus(KeyStatusType.ENABLED)
                    .setKeyId(1)
                    .setOutputPrefixType(OutputPrefixType.RAW)
                    .build()
            )
            .build()

        val privHandle = CleartextKeysetHandle.fromKeyset(privKeyset)
        val decryptor = privHandle.getPrimitive(HybridDecrypt::class.java)

        val cleartextBytes = try {
            decryptor.decrypt(ciphertext, CONTEXT_INFO)
        } catch (e: GeneralSecurityException) {
            throw DecryptionException("Failed to unwrap export data key: wrong private key or corrupted export header", e)
        }

        return try {
            CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(cleartextBytes))
        } catch (e: Exception) {
            throw DecryptionException("Failed to parse unwrapped Tink keyset handle", e)
        }
    }

    fun getStreamingAead(dataKeysetHandle: KeysetHandle): StreamingAead {
        return dataKeysetHandle.getPrimitive(StreamingAead::class.java)
    }

    /**
     * Encrypts an [InputStream] to an [OutputStream] using Tink Streaming AEAD.
     */
    fun encryptStream(
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

    /**
     * Decrypts an [InputStream] to an [OutputStream] using Tink Streaming AEAD.
     * Throws [TamperedCiphertextException] on integrity or authentication tag failure.
     */
    fun decryptStream(
        streamingAead: StreamingAead,
        inputStream: InputStream,
        outputStream: OutputStream,
        associatedData: ByteArray = ByteArray(0)
    ) {
        try {
            streamingAead.newDecryptingStream(inputStream, associatedData).use { decryptingStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (decryptingStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }
        } catch (e: GeneralSecurityException) {
            throw TamperedCiphertextException("Authentication failed while decrypting ciphertext stream: data has been tampered with or corrupted", e)
        } catch (e: Exception) {
            throw TamperedCiphertextException("Error while reading decrypting stream: ${e.message}", e)
        }
    }

    fun encryptBytes(
        streamingAead: StreamingAead,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        val bais = ByteArrayInputStream(plaintext)
        val baos = ByteArrayOutputStream()
        encryptStream(streamingAead, bais, baos, associatedData)
        return baos.toByteArray()
    }

    fun decryptBytes(
        streamingAead: StreamingAead,
        ciphertext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        val bais = ByteArrayInputStream(ciphertext)
        val baos = ByteArrayOutputStream()
        decryptStream(streamingAead, bais, baos, associatedData)
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

    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519.generatePrivateKey()
        val pub = X25519.publicFromPrivate(priv)
        return Pair(priv, pub)
    }

    fun createSession(): ExportSession {
        return ExportSession(generateDataKeysetHandle())
    }

    fun restoreSession(serializedKeysetBase64: String): ExportSession {
        val bytes = Base64.getDecoder().decode(serializedKeysetBase64)
        val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(bytes))
        return ExportSession(handle)
    }
}

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

    fun serializeKeyset(): String {
        val baos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(keysetHandle, BinaryKeysetWriter.withOutputStream(baos))
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
