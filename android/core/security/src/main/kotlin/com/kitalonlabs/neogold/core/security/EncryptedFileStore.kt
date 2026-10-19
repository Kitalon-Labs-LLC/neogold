package com.kitalonlabs.neogold.core.security

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

interface EncryptedFileStore {
    /**
     * Encrypts and writes data to a temporary file using Tink StreamingAead,
     * fsyncs the descriptor to storage hardware, and atomically renames to [fileName].
     *
     * The plaintext data is NEVER written to disk in unencrypted form.
     * Returns the SHA-256 hex string of the plaintext bytes for database recording (`image_sha256`).
     */
    fun writeEncryptedFile(
        fileName: String,
        associatedData: ByteArray = ByteArray(0),
        writeAction: (OutputStream) -> Unit
    ): String

    /**
     * Opens an [InputStream] that decrypts [fileName] on the fly via Tink StreamingAead.
     */
    fun openDecryptedStream(
        fileName: String,
        associatedData: ByteArray = ByteArray(0)
    ): InputStream

    fun getEncryptedFile(fileName: String): File
    fun exists(fileName: String): Boolean
    fun delete(fileName: String): Boolean
    fun deleteAllFiles()
}

class DefaultEncryptedFileStore(
    private val context: Context,
    private val fileKeysetProvider: FileKeysetProvider,
    private val baseDirectory: File = File(context.filesDir, "research_images")
) : EncryptedFileStore {

    init {
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs()
        }
    }

    override fun writeEncryptedFile(
        fileName: String,
        associatedData: ByteArray,
        writeAction: (OutputStream) -> Unit
    ): String {
        val targetFile = File(baseDirectory, fileName)
        val tempFile = File(baseDirectory, "${fileName}.${UUID.randomUUID()}.tmp")

        val digest = MessageDigest.getInstance("SHA-256")

        try {
            FileOutputStream(tempFile).use { rawFos ->
                val encryptingStream = fileKeysetProvider.getStreamingAead()
                    .newEncryptingStream(rawFos, associatedData)

                DigestOutputStream(encryptingStream, digest).use { digestOut ->
                    writeAction(digestOut)
                    digestOut.flush()
                }

                rawFos.flush()
                try {
                    rawFos.fd.sync()
                } catch (_: Exception) {}
            }

            // Atomic rename
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw IOException("Failed to rename temporary encrypted file to $targetFile")
                }
            }

            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    override fun openDecryptedStream(
        fileName: String,
        associatedData: ByteArray
    ): InputStream {
        val file = File(baseDirectory, fileName)
        if (!file.exists()) {
            throw IOException("Encrypted file not found: $fileName")
        }
        val rawFis = FileInputStream(file)
        return fileKeysetProvider.getStreamingAead().newDecryptingStream(rawFis, associatedData)
    }

    override fun getEncryptedFile(fileName: String): File {
        return File(baseDirectory, fileName)
    }

    override fun exists(fileName: String): Boolean {
        return File(baseDirectory, fileName).exists()
    }

    override fun delete(fileName: String): Boolean {
        val file = File(baseDirectory, fileName)
        return if (file.exists()) file.delete() else false
    }

    override fun deleteAllFiles() {
        val files = baseDirectory.listFiles() ?: return
        for (f in files) {
            f.delete()
        }
    }
}
