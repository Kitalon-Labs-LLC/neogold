package com.kitalonlabs.neogold.core.security

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import org.junit.Assert.assertArrayEquals
import org.junit.BeforeClass
import org.junit.Test
import java.nio.charset.StandardCharsets

/** HPKE (X25519, HKDF-SHA256, AES-256-GCM) is available in the Tink version the app ships, including from raw key bytes. */
class TinkHpkeAvailabilityTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            HybridConfig.register()
        }
    }

    private fun exportParameters(): HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    @Test
    fun testHpkeTemplatesAvailable() {
        val privateHandle = KeysetHandle.generateNew(exportParameters())
        val publicHandle = privateHandle.publicKeysetHandle

        val encryptor = publicHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        val decryptor = privateHandle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

        val plaintext = "Hello NeoGold HPKE".toByteArray(StandardCharsets.UTF_8)
        val contextInfo = "test-context".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = encryptor.encrypt(plaintext, contextInfo)
        val decrypted = decryptor.decrypt(ciphertext, contextInfo)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testHpkeFromRawPublicKeyBytes() {
        val privateHandle = KeysetHandle.generateNew(exportParameters())

        // Extract the raw key bytes from the generated key, as the study's offline key would be handed over
        val privateKey = privateHandle.primary.key as HpkePrivateKey
        val rawPublicBytes = privateKey.publicKey.publicKeyBytes.toByteArray()
        val rawPrivateBytes = privateKey.privateKeyBytes.toByteArray(InsecureSecretKeyAccess.get())

        // Rebuild both halves from the raw bytes, with no key prefix (the export format)
        val publicKey = HpkePublicKey.create(
            privateKey.parameters,
            Bytes.copyFrom(rawPublicBytes),
            null
        )
        val manualPublicHandle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(publicKey).withFixedId(1).makePrimary())
            .build()
        val encryptor = manualPublicHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

        val plaintext = "Export Secret Data Key 12345678".toByteArray(StandardCharsets.UTF_8)
        val contextInfo = "neogold-export-v1".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = encryptor.encrypt(plaintext, contextInfo)

        val manualPrivateKey = HpkePrivateKey.create(
            publicKey,
            SecretBytes.copyFrom(rawPrivateBytes, InsecureSecretKeyAccess.get())
        )
        val manualPrivateHandle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(manualPrivateKey).withFixedId(1).makePrimary())
            .build()
        val manualDecryptor = manualPrivateHandle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

        assertArrayEquals(plaintext, manualDecryptor.decrypt(ciphertext, contextInfo))
    }
}
