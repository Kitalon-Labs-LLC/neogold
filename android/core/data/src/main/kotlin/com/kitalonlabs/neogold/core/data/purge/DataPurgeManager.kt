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

interface DataPurgeManager {
    /**
     * Delete all data (FR-33):
     * Wipes database, encrypted files, wrapped keys, and settings;
     * then recreates an empty database with fresh keys and records DATA_DELETED in the new audit log.
     */
    suspend fun deleteAllData(): NeoGoldDatabase
}

class DefaultDataPurgeManager(
    private val context: Context,
    private val database: NeoGoldDatabase,
    private val databaseKeyProvider: DatabaseKeyProvider,
    private val fileKeysetProvider: FileKeysetProvider,
    private val encryptedFileStore: EncryptedFileStore,
    private val dataStore: NeoGoldDataStore,
    private val databaseName: String = NeoGoldDatabase.DATABASE_NAME,
    private val openHelperFactory: SupportSQLiteOpenHelper.Factory? = null
) : DataPurgeManager {

    override suspend fun deleteAllData(): NeoGoldDatabase {
        // 1. Close active database connection
        if (database.isOpen) {
            database.close()
        }

        // 2. Delete database files
        context.deleteDatabase(databaseName)
        File(context.filesDir, databaseName).delete()
        File(context.filesDir, "$databaseName-wal").delete()
        File(context.filesDir, "$databaseName-shm").delete()

        // 3. Delete encrypted files
        encryptedFileStore.deleteAllFiles()

        // 4. Delete wrapped keys and master keys
        databaseKeyProvider.deleteKey()
        fileKeysetProvider.deleteKeyset()

        // 5. Clear DataStore preferences
        dataStore.clearAll()

        // 6. Recreate fresh empty database with fresh keys
        val freshDatabase = NeoGoldDatabase.buildDatabase(
            context = context,
            databaseKeyProvider = databaseKeyProvider,
            databaseName = databaseName,
            openHelperFactory = openHelperFactory
        )

        // 7. Record DATA_DELETED in fresh audit log
        freshDatabase.auditEventDao().insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                eventType = "DATA_DELETED",
                details = """{"reason":"USER_ACTION_FR_33"}"""
            )
        )

        return freshDatabase
    }
}
