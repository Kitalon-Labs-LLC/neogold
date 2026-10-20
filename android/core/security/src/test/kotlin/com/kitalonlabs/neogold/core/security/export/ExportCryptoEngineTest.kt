package com.kitalonlabs.neogold.core.security.export

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class ExportCryptoEngineTest {

    @Test
    fun testWrapAndUnwrapRoundTrip() {
        val privateKeyBytes = X25519.generatePrivateKey()
        val publicKeyBytes = X25519.publicFromPrivate(privateKeyBytes)

        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val wrappedKeyB64 = ExportCryptoEngine.wrapDataKey(dataKeysetHandle, publicKeyBytes)
        assertNotNull(wrappedKeyB64)
        assertTrue(wrappedKeyB64.isNotEmpty())

        val unwrappedKeysetHandle = ExportCryptoEngine.unwrapDataKey(wrappedKeyB64, privateKeyBytes)
        assertNotNull(unwrappedKeysetHandle)

        // Verify that the unwrapped StreamingAead can decrypt what dataKeysetHandle encrypted
        val encAead = ExportCryptoEngine.getStreamingAead(dataKeysetHandle)
        val decAead = ExportCryptoEngine.getStreamingAead(unwrappedKeysetHandle)

        val plaintext = "Test research session CSV plaintext contents 123456789".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = ExportCryptoEngine.encryptBytes(encAead, plaintext)
        val decrypted = ExportCryptoEngine.decryptBytes(decAead, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testWrongPrivateKeyFails() {
        val privKey1 = X25519.generatePrivateKey()
        val pubKey1 = X25519.publicFromPrivate(privKey1)

        val privKey2 = X25519.generatePrivateKey()

        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val wrappedKeyB64 = ExportCryptoEngine.wrapDataKey(dataKeysetHandle, pubKey1)

        try {
            ExportCryptoEngine.unwrapDataKey(wrappedKeyB64, privKey2)
            fail("Expected DecryptionException when using wrong private key")
        } catch (e: DecryptionException) {
            assertTrue(e.message?.contains("wrong private key") == true)
        }
    }

    @Test
    fun testTamperedCiphertextFails() {
        val privKey = X25519.generatePrivateKey()
        val pubKey = X25519.publicFromPrivate(privKey)

        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val aead = ExportCryptoEngine.getStreamingAead(dataKeysetHandle)

        val plaintext = "Confidential clinical measurements".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = ExportCryptoEngine.encryptBytes(aead, plaintext)

        // Tamper with ciphertext by flipping a bit in the middle
        val tampered = ciphertext.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0xFF).toByte()

        try {
            ExportCryptoEngine.decryptBytes(aead, tampered)
            fail("Expected TamperedCiphertextException when ciphertext is modified")
        } catch (e: TamperedCiphertextException) {
            assertTrue(e.message?.contains("tampered") == true || e.cause != null)
        }
    }

    @Test
    fun testStreamEncryptionDecryption() {
        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val aead = ExportCryptoEngine.getStreamingAead(dataKeysetHandle)

        // 128 KB payload
        val largeData = ByteArray(128 * 1024) { (it % 256).toByte() }

        val inStream = ByteArrayInputStream(largeData)
        val cipherOutStream = ByteArrayOutputStream()
        ExportCryptoEngine.encryptStream(aead, inStream, cipherOutStream)

        val ciphertext = cipherOutStream.toByteArray()
        assertTrue(ciphertext.size > largeData.size)

        val cipherInStream = ByteArrayInputStream(ciphertext)
        val plainOutStream = ByteArrayOutputStream()
        ExportCryptoEngine.decryptStream(aead, cipherInStream, plainOutStream)

        assertArrayEquals(largeData, plainOutStream.toByteArray())
    }

    @Test
    fun testExportHeaderSerialization() {
        val header = ExportHeader(
            studyCode = "SKP",
            exportedAt = "2026-10-20T12:00:00Z",
            totalParticipants = 5,
            totalSessions = 10,
            totalImages = 20,
            wrappedDataKeyB64 = "ABCD1234==",
            manifestSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )

        val jsonStr = header.toJsonString()
        val parsed = ExportHeader.fromJsonString(jsonStr)

        assertEquals(header.studyCode, parsed.studyCode)
        assertEquals(header.exportedAt, parsed.exportedAt)
        assertEquals(header.totalParticipants, parsed.totalParticipants)
        assertEquals(header.totalSessions, parsed.totalSessions)
        assertEquals(header.totalImages, parsed.totalImages)
        assertEquals(header.wrappedDataKeyB64, parsed.wrappedDataKeyB64)
        assertEquals(header.manifestSha256, parsed.manifestSha256)
    }

    @Test
    fun testManifestParser() {
        val entries = listOf(
            ManifestEntry("a1b2c3d4", "data/sessions.csv.enc"),
            ManifestEntry("e5f6a7b8", "images/cap1.jpg.enc")
        )

        val formatted = ManifestParser.format(entries)
        val parsed = ManifestParser.parse(formatted)

        assertEquals(2, parsed.size)
        assertEquals("a1b2c3d4", parsed[0].sha256Hex)
        assertEquals("data/sessions.csv.enc", parsed[0].relativePath)
        assertEquals("e5f6a7b8", parsed[1].sha256Hex)
        assertEquals("images/cap1.jpg.enc", parsed[1].relativePath)
    }
}
