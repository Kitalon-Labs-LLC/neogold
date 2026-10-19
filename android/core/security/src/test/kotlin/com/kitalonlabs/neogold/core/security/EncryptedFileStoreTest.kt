package com.kitalonlabs.neogold.core.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class EncryptedFileStoreTest {

    private lateinit var context: Context
    private lateinit var storageDir: File
    private lateinit var keyDir: File
    private lateinit var masterKeyProvider: MasterKeyProvider
    private lateinit var fileKeysetProvider: FileKeysetProvider
    private lateinit var encryptedFileStore: EncryptedFileStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageDir = File(context.filesDir, "test_research_images")
        keyDir = File(context.filesDir, "test_file_keys")
        storageDir.mkdirs()
        keyDir.mkdirs()

        masterKeyProvider = FakeMasterKeyProvider()
        fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider, keyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, storageDir)
    }

    @After
    fun tearDown() {
        encryptedFileStore.deleteAllFiles()
        fileKeysetProvider.deleteKeyset()
        storageDir.deleteRecursively()
        keyDir.deleteRecursively()
    }

    @Test
    fun writeEncryptedFile_writesCiphertextNeverPlaintextAndReturnsPlaintextSha256() {
        val fileName = "capture_sternum_01.jpg"
        val fakeJpegBytes = "RAW_JPEG_IMAGE_PIXELS_FOR_RESEARCH".toByteArray(Charsets.UTF_8)
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(fakeJpegBytes)
            .joinToString("") { "%02x".format(it) }

        val returnedSha256 = encryptedFileStore.writeEncryptedFile(fileName) { outputStream ->
            outputStream.write(fakeJpegBytes)
        }

        assertThat(returnedSha256).isEqualTo(expectedDigest)
        assertThat(encryptedFileStore.exists(fileName)).isTrue()

        // Verify the raw file on disk is NOT plaintext JPEG bytes (TC-RSH-13, TC-LOG-07)
        val rawFile = encryptedFileStore.getEncryptedFile(fileName)
        val rawOnDiskBytes = FileInputStream(rawFile).use { it.readBytes() }
        assertThat(rawOnDiskBytes).isNotEqualTo(fakeJpegBytes)
        assertThat(String(rawOnDiskBytes)).doesNotContain("RAW_JPEG_IMAGE_PIXELS_FOR_RESEARCH")

        // Decrypt stream and verify content matches original plaintext
        val decryptedBytes = encryptedFileStore.openDecryptedStream(fileName).use { it.readBytes() }
        assertThat(decryptedBytes).isEqualTo(fakeJpegBytes)
    }

    @Test
    fun deleteAndExistsWorkAsExpected() {
        val fileName = "temp_capture.jpg"
        encryptedFileStore.writeEncryptedFile(fileName) { it.write("Data".toByteArray()) }
        assertThat(encryptedFileStore.exists(fileName)).isTrue()

        val deleted = encryptedFileStore.delete(fileName)
        assertThat(deleted).isTrue()
        assertThat(encryptedFileStore.exists(fileName)).isFalse()
    }
}
