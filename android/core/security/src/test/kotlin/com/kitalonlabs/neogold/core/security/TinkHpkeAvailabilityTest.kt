package com.kitalonlabs.neogold.core.security

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
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
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import java.nio.charset.StandardCharsets

class TinkHpkeAvailabilityTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            HybridConfig.register()
        }
    }

    @Test
    fun testHpkeTemplatesAvailable() {
        val template = KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")
        assertNotNull(template)

        val privateHandle = KeysetHandle.generateNew(template)
        val publicHandle = privateHandle.publicKeysetHandle

        val encryptor = publicHandle.getPrimitive(HybridEncrypt::class.java)
        val decryptor = privateHandle.getPrimitive(HybridDecrypt::class.java)

        val plaintext = "Hello NeoGold HPKE".toByteArray(StandardCharsets.UTF_8)
        val contextInfo = "test-context".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = encryptor.encrypt(plaintext, contextInfo)
        val decrypted = decryptor.decrypt(ciphertext, contextInfo)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testHpkeFromRawPublicKeyBytes() {
        val template = KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")
        val privateHandle = KeysetHandle.generateNew(template)

        // Extract raw public key bytes from privateHandle
        val privateKeyset = CleartextKeysetHandle.getKeyset(privateHandle)
        val privKeyProto = HpkePrivateKey.parseFrom(privateKeyset.getKey(0).keyData.value)
        val rawPublicBytes = privKeyProto.publicKey.publicKey.toByteArray()
        val rawPrivateBytes = privKeyProto.privateKey.toByteArray()

        // Reconstruct public keyset from rawPublicBytes
        val hpkePubKeyProto = HpkePublicKey.newBuilder()
            .setVersion(0)
            .setParams(
                HpkeParams.newBuilder()
                    .setKem(HpkeKem.DHKEM_X25519_HKDF_SHA256)
                    .setKdf(HpkeKdf.HKDF_SHA256)
                    .setAead(HpkeAead.AES_256_GCM)
                    .build()
            )
            .setPublicKey(ByteString.copyFrom(rawPublicBytes))
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

        val pubKeysetHandle = CleartextKeysetHandle.fromKeyset(pubKeyset)
        val encryptor = pubKeysetHandle.getPrimitive(HybridEncrypt::class.java)

        // Decrypt with original privateHandle
        val decryptor = privateHandle.getPrimitive(HybridDecrypt::class.java)

        val plaintext = "Export Secret Data Key 12345678".toByteArray(StandardCharsets.UTF_8)
        val contextInfo = "neogold-export-v1".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = encryptor.encrypt(plaintext, contextInfo)

        // Reconstruct private keyset with RAW prefix too
        val hpkePrivKeyProto = HpkePrivateKey.newBuilder()
            .setVersion(0)
            .setPublicKey(hpkePubKeyProto)
            .setPrivateKey(ByteString.copyFrom(rawPrivateBytes))
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

        val manualPrivHandle = CleartextKeysetHandle.fromKeyset(privKeyset)
        val manualDecryptor = manualPrivHandle.getPrimitive(HybridDecrypt::class.java)

        val decrypted = manualDecryptor.decrypt(ciphertext, contextInfo)
        assertArrayEquals(plaintext, decrypted)
    }
}
