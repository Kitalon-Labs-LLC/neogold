package com.kitalonlabs.neogold.core.data.purge

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.security.DatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FileKeysetProvider
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * What "Delete all data" left behind, if anything. [incomplete] names the steps and [DataWiper]s that could not finish,
 * and is empty when everything is gone.
 */
data class PurgeResult(val database: NeoGoldDatabase, val incomplete: List<String>)

interface DataPurgeManager {
    /**
     * Delete all data (FR-33):
     * Wipes database, encrypted files, wrapped keys, settings and whatever the app's [DataWiper]s cover;
     * then recreates an empty database with fresh keys and records DATA_DELETED in the new audit log.
     */
    suspend fun deleteAllData(): PurgeResult
}

class DefaultDataPurgeManager(
    private val context: Context,
    private val database: NeoGoldDatabase,
    private val databaseKeyProvider: DatabaseKeyProvider,
    private val fileKeysetProvider: FileKeysetProvider,
    private val encryptedFileStore: EncryptedFileStore,
    private val dataStore: NeoGoldDataStore,
    private val databaseName: String = NeoGoldDatabase.DATABASE_NAME,
    private val openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
    private val wipers: List<DataWiper> = emptyList()
) : DataPurgeManager {

    /**
     * Every step runs even if an earlier one fails, and a step that fails is named in the result instead of hidden.
     * The keys go first: once the database key and the file keyset are gone, whatever file is left is unreadable
     * ciphertext, so a process killed halfway leaves no readable data behind.
     */
    override suspend fun deleteAllData(): PurgeResult {
        val incomplete = mutableListOf<String>()

        step("database-connection", incomplete) { if (database.isOpen) database.close() }

        // 1. Keys: the Keystore master keys and their wrapped copies for the database and the research files
        step("database-key", incomplete) { databaseKeyProvider.deleteKey() }
        step("file-keyset", incomplete) { fileKeysetProvider.deleteKeyset() }

        // 2. Every other key and folder the app keeps: K9, K6, crash key, export staging, crash reports
        wipers.filterNot { it.wipedCompletely() }.mapTo(incomplete) { it.name }

        // 3. Files
        step("database-files", incomplete) {
            context.deleteDatabase(databaseName)
            File(context.filesDir, databaseName).delete()
            File(context.filesDir, "$databaseName-wal").delete()
            File(context.filesDir, "$databaseName-shm").delete()
        }
        step("encrypted-files", incomplete) { encryptedFileStore.deleteAllFiles() }

        // 4. Settings
        step("settings", incomplete) { dataStore.clearAll() }

        // 5. A fresh empty database with fresh keys, and the record that this happened (names of failed steps are not
        // personal data)
        val freshDatabase = NeoGoldDatabase.buildDatabase(
            context = context,
            databaseKeyProvider = databaseKeyProvider,
            databaseName = databaseName,
            openHelperFactory = openHelperFactory
        )
        freshDatabase.auditEventDao().insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                eventType = "DATA_DELETED",
                details = deletedAuditDetails(incomplete)
            )
        )

        return PurgeResult(freshDatabase, incomplete)
    }

    private fun deletedAuditDetails(incomplete: List<String>): String {
        if (incomplete.isEmpty()) return """{"reason":"USER_ACTION_FR_33"}"""
        val names = incomplete.joinToString(",") { "\"$it\"" }
        return """{"reason":"USER_ACTION_FR_33","incomplete":[$names]}"""
    }

    @Suppress("TooGenericExceptionCaught") // a step that fails must not stop the others; it is reported by name
    private suspend fun step(name: String, incomplete: MutableList<String>, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            incomplete += name
        }
    }

    @Suppress("TooGenericExceptionCaught") // a wiper that fails must not stop the others or the fresh database
    private fun DataWiper.wipedCompletely(): Boolean = try {
        wipe()
    } catch (_: Exception) {
        false
    }
}
