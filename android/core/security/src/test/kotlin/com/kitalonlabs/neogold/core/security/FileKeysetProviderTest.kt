package com.kitalonlabs.neogold.core.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileKeysetProviderTest {

    private lateinit var context: Context
    private lateinit var keyDir: File
    private lateinit var masterKeyProvider: MasterKeyProvider
    private lateinit var fileKeysetProvider: FileKeysetProvider

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        keyDir = File(context.filesDir, "test_file_security")
        keyDir.mkdirs()
        masterKeyProvider = FakeMasterKeyProvider()
        fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider, keyDir)
    }

    @After
    fun tearDown() {
        fileKeysetProvider.deleteKeyset()
        keyDir.deleteRecursively()
    }

    @Test
    fun getStreamingAead_generatesAndEncryptsDecryptsStream() {
        val streamingAead = fileKeysetProvider.getStreamingAead()
        assertThat(streamingAead).isNotNull()
        assertThat(fileKeysetProvider.hasWrappedKeyset()).isTrue()

        val plaintext = "NeoGold research image raw stream data".toByteArray(Charsets.UTF_8)
        val associatedData = "metadata-associated-data".toByteArray(Charsets.UTF_8)

        // Encrypt stream
        val cipherOutputStream = ByteArrayOutputStream()
        val encryptingStream = streamingAead.newEncryptingStream(cipherOutputStream, associatedData)
        encryptingStream.write(plaintext)
        encryptingStream.close()

        val ciphertext = cipherOutputStream.toByteArray()
        assertThat(ciphertext).isNotEqualTo(plaintext)

        // Decrypt stream
        val cipherInputStream = ByteArrayInputStream(ciphertext)
        val decryptingStream = streamingAead.newDecryptingStream(cipherInputStream, associatedData)
        val decryptedBytes = decryptingStream.readBytes()
        decryptingStream.close()

        assertThat(decryptedBytes).isEqualTo(plaintext)
    }

    @Test
    fun keysetPersistsAcrossProviderInstances() {
        val streamingAead1 = fileKeysetProvider.getStreamingAead()

        val plaintext = "Test Persistence Data".toByteArray()
        val cipherOutputStream = ByteArrayOutputStream()
        val encStream = streamingAead1.newEncryptingStream(cipherOutputStream, ByteArray(0))
        encStream.write(plaintext)
        encStream.close()

        // Create new provider instance
        val provider2 = DefaultFileKeysetProvider(context, masterKeyProvider, keyDir)
        val streamingAead2 = provider2.getStreamingAead()

        val decStream = streamingAead2.newDecryptingStream(
            ByteArrayInputStream(cipherOutputStream.toByteArray()),
            ByteArray(0)
        )
        val decrypted = decStream.readBytes()
        decStream.close()

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun deleteKeyset_removesWrappedFile() {
        fileKeysetProvider.getStreamingAead()
        assertThat(fileKeysetProvider.hasWrappedKeyset()).isTrue()

        fileKeysetProvider.deleteKeyset()
        assertThat(fileKeysetProvider.hasWrappedKeyset()).isFalse()
    }
}
