package com.kitalonlabs.neogold.core.security

import android.content.Context
import timber.log.Timber
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed interface KeystoreHealthStatus {
    object Healthy : KeystoreHealthStatus
    data class LossDetected(val reason: String, val cause: Throwable?) : KeystoreHealthStatus
}

interface KeystoreRecoveryManager {
    /**
     * Checks if Keystore loss has occurred (i.e. wrapped keys exist on disk,
     * but the Keystore master keys are missing or fail to unwrap them).
     * Returns [KeystoreHealthStatus.Healthy] or [KeystoreHealthStatus.LossDetected].
     */
    fun checkKeystoreHealth(): KeystoreHealthStatus

    /**
     * Resets all encrypted stores following a Keystore loss event.
     * Deletes the encrypted database, research image files, and orphaned wrapped key files,
     * and prepares fresh empty stores so the app can continue working without crashing.
     */
    fun resetEncryptedStores()
}

class DefaultKeystoreRecoveryManager(
    private val context: Context,
    private val databaseKeyProvider: DatabaseKeyProvider,
    private val fileKeysetProvider: FileKeysetProvider,
    private val encryptedFileStore: EncryptedFileStore,
    private val databaseName: String = "neogold.db"
) : KeystoreRecoveryManager {

    @Suppress("TooGenericExceptionCaught") // a probe that fails in any way means the stores cannot be trusted
    override fun checkKeystoreHealth(): KeystoreHealthStatus {
        return try {
            if (databaseKeyProvider.hasWrappedKey()) {
                databaseKeyProvider.verifyKeyDecryption()
            }
            if (fileKeysetProvider.hasWrappedKeyset()) {
                fileKeysetProvider.verifyKeysetDecryption()
            }
            KeystoreHealthStatus.Healthy
        } catch (e: KeystoreLossException) {
            Timber.w("Keystore health probe found a lost key: %s", e.javaClass.name)
            KeystoreHealthStatus.LossDetected(e.message ?: "Keystore key missing or unrecoverable", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not Throwable: an OutOfMemoryError is not a lost key. Class name only, never the message (doc 8 6.6).
            Timber.w("Keystore health probe failed: %s", e.javaClass.name)
            KeystoreHealthStatus.LossDetected("Keystore error: ${e.javaClass.simpleName}", e)
        }
    }

    override fun resetEncryptedStores() {
        // 1. Wipe database files
        context.deleteDatabase(databaseName)
        File(context.filesDir, databaseName).delete()
        File(context.filesDir, "$databaseName-wal").delete()
        File(context.filesDir, "$databaseName-shm").delete()

        // 2. Wipe wrapped key files and master keys
        databaseKeyProvider.deleteKey()
        fileKeysetProvider.deleteKeyset()

        // 3. Wipe research files
        encryptedFileStore.deleteAllFiles()
    }
}
