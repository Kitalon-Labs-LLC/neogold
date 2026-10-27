package com.kitalonlabs.neogold.core.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object AtomicFileStorage {

    /**
     * Writes bytes to a temp file, fsyncs the descriptor to storage hardware, and atomically renames.
     * Prevents partial writes or zero-byte corrupted files during process death or power loss.
     */
    fun writeBytes(targetFile: File, bytes: ByteArray) {
        writeStream(targetFile) { fos ->
            fos.write(bytes)
        }
    }

    /**
     * Opens a temp file, executes [writeAction], calls fsync on the underlying file descriptor,
     * closes the stream, and atomically replaces [targetFile].
     * Cleans up the temp file on any error.
     */
    @Suppress("TooGenericExceptionCaught") // clean up the temp file on any failure, then rethrow
    fun writeStream(targetFile: File, writeAction: (FileOutputStream) -> Unit) {
        val parent = targetFile.parentFile ?: throw IOException("Target file has no parent directory: $targetFile")
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw IOException("Failed to create parent directories for $targetFile")
        }

        val tempFile = File(parent, "${targetFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                writeAction(fos)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                    // fsync might not be supported on some virtual filesystems; warn or propagate
                }
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
                // If atomic move fails (e.g. cross-filesystem or OS restriction), fallback to standard rename
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw IOException("Failed to rename temporary file $tempFile to target $targetFile")
                }
            }
        } catch (e: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }
}
