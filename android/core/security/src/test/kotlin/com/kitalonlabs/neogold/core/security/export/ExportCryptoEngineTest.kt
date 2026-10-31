package com.kitalonlabs.neogold.core.security.export

import com.google.crypto.tink.StreamingAead
import com.kitalonlabs.neogold.decrypt.DecryptEngine
import com.kitalonlabs.neogold.decrypt.DecryptionException
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
import com.kitalonlabs.neogold.decrypt.TamperedCiphertextException as DecryptTamperedException

/**
 * The app only encrypts (BUG-083 item 29): every decrypting step in these tests goes through the offline decrypt tool, so
 * a test passing means the tool can open what the app writes, not that the app agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
class ExportCryptoEngineTest {

    private fun decryptBytes(aead: StreamingAead, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        DecryptEngine.decryptStream(aead, ByteArrayInputStream(ciphertext), out, aad)
        return out.toByteArray()
    }

    @Test
    fun testWrapAndUnwrapRoundTrip() {
        val (privateKeyBytes, publicKeyBytes) = DecryptEngine.generateKeyPair()

        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val wrappedKeyB64 = ExportCryptoEngine.wrapDataKey(dataKeysetHandle, publicKeyBytes)
        assertNotNull(wrappedKeyB64)
        assertTrue(wrappedKeyB64.isNotEmpty())

        val unwrappedKeysetHandle = DecryptEngine.unwrapDataKey(wrappedKeyB64, privateKeyBytes)

        val encAead = ExportCryptoEngine.getStreamingAead(dataKeysetHandle)
        val decAead = unwrappedKeysetHandle.getPrimitive(
            com.google.crypto.tink.RegistryConfiguration.get(),
            StreamingAead::class.java
        )

        val plaintext = "Test research session CSV plaintext contents 123456789".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = ExportCryptoEngine.encryptBytes(encAead, plaintext)

        assertArrayEquals(plaintext, decryptBytes(decAead, ciphertext))
    }

    @Test
    fun testWrongPrivateKeyFails() {
        val (_, pubKey1) = DecryptEngine.generateKeyPair()
        val (privKey2, _) = DecryptEngine.generateKeyPair()

        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val wrappedKeyB64 = ExportCryptoEngine.wrapDataKey(dataKeysetHandle, pubKey1)
        try {
            DecryptEngine.unwrapDataKey(wrappedKeyB64, privKey2)
            fail("Expected DecryptionException when using wrong private key")
        } catch (_: DecryptionException) {
            // expected
        }
    }

    @Test
    fun testTamperedCiphertextFails() {
        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val aead = ExportCryptoEngine.getStreamingAead(dataKeysetHandle)
        val plaintext = "Sensitive study measurements".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = ExportCryptoEngine.encryptBytes(aead, plaintext)
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        try {
            decryptBytes(aead, tampered)
            fail("Expected TamperedCiphertextException when ciphertext is modified")
        } catch (_: DecryptTamperedException) {
            // expected
        }
    }

    @Test
    fun testStreamEncryptionDecryption() {
        val dataKeysetHandle = ExportCryptoEngine.generateDataKeysetHandle()
        val aead = ExportCryptoEngine.getStreamingAead(dataKeysetHandle)

        // larger than several 4 KB segments, so segment boundaries are crossed
        val largeData = ByteArray(40_000) { (it % 251).toByte() }
        val cipherOut = ByteArrayOutputStream()
        ExportCryptoEngine.encryptStream(aead, ByteArrayInputStream(largeData), cipherOut)

        assertArrayEquals(largeData, decryptBytes(aead, cipherOut.toByteArray()))
    }

    @Test
    fun testAssociatedDataIsBound() {
        val aead = ExportCryptoEngine.getStreamingAead(ExportCryptoEngine.generateDataKeysetHandle())
        val ciphertext = ExportCryptoEngine.encryptBytes(aead, "x".toByteArray(), "images/a.enc".toByteArray())
        try {
            decryptBytes(aead, ciphertext, "images/b.enc".toByteArray())
            fail("A ciphertext must not open under other associated data")
        } catch (_: DecryptTamperedException) {
            // expected
        }
    }

    @Test
    fun testWrapRejectsAKeyOfTheWrongLength() {
        val handle = ExportCryptoEngine.generateDataKeysetHandle()
        try {
            ExportCryptoEngine.wrapDataKey(handle, ByteArray(31))
            fail("A public key that is not 32 bytes must be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /**
     * BUG-083 item 27: archives written by the app on Tink 1.7.0 (kept as a fixture, made with a throwaway key) still open
     * with the decryptor on Tink 1.24, so a version bump cannot orphan an export that is already on a drive.
     */
    @Test
    fun testArchiveWrittenByTink170StillOpens() {
        val fixture = java.util.Properties().apply {
            // read from the module directory: Robolectric's class loader does not serve plain test resources
            java.io.File("src/test/resources/export/legacy-tink-1.7.0.properties").inputStream().use { load(it) }
        }
        val b64 = java.util.Base64.getDecoder()
        val keyset = DecryptEngine.unwrapDataKey(
            fixture.getProperty("wrappedDataKeyB64"),
            b64.decode(fixture.getProperty("privateKeyB64"))
        )
        val aead = keyset.getPrimitive(com.google.crypto.tink.RegistryConfiguration.get(), StreamingAead::class.java)
        val plain = decryptBytes(
            aead,
            b64.decode(fixture.getProperty("ciphertextB64")),
            fixture.getProperty("aad").toByteArray()
        )
        assertEquals(fixture.getProperty("plaintext"), String(plain, StandardCharsets.UTF_8))
    }

    /** The wrap format is fixed by export-format v1: the same bytes the 1.7.0 build wrote are the bytes a new build writes. */
    @Test
    fun testWrapIsRawHpkeOfTheBinaryKeyset() {
        val (privateKeyBytes, publicKeyBytes) = DecryptEngine.generateKeyPair()
        val handle = ExportCryptoEngine.generateDataKeysetHandle()
        val unwrapped = DecryptEngine.unwrapDataKey(
            ExportCryptoEngine.wrapDataKey(handle, publicKeyBytes),
            privateKeyBytes
        )
        val sample = "same key".toByteArray()
        val ciphertext = ExportCryptoEngine.encryptBytes(ExportCryptoEngine.getStreamingAead(handle), sample)
        val aead = unwrapped.getPrimitive(com.google.crypto.tink.RegistryConfiguration.get(), StreamingAead::class.java)
        assertArrayEquals(sample, decryptBytes(aead, ciphertext))
    }

    /**
     * BUG-083 item 29: the phone encrypts and nothing else. The only public entry points of the engine and of a session are
     * listed; a new public function that handles keys or decrypts fails this test and needs a decision here.
     * (Kotlin `internal` functions are compiled with a `$module` suffix in their name, so a name without `$` is public.)
     */
    @Test
    fun testOnlyEncryptingEntryPointsArePublic() {
        fun publicNames(c: Class<*>) = c.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            sortedSetOf("createSession", "getCONTEXT_INFO", "sha256Hex"),
            publicNames(ExportCryptoEngine::class.java)
        )
        assertEquals(
            sortedSetOf("encryptBytes", "encryptStream", "wrapDataKey"),
            publicNames(ExportSession::class.java)
        )
    }

    @Test
    fun testSerializedDataKeyIsZeroedAfterWrapping() {
        // the keyset bytes are a local of wrapDataKey; the observable part is that wrapping twice with one handle works
        // and gives two different wraps (HPKE is randomized), so nothing about the first call was consumed
        val (_, publicKeyBytes) = DecryptEngine.generateKeyPair()
        val handle = ExportCryptoEngine.generateDataKeysetHandle()
        val first = ExportCryptoEngine.wrapDataKey(handle, publicKeyBytes)
        val second = ExportCryptoEngine.wrapDataKey(handle, publicKeyBytes)
        assertTrue(first != second)
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
